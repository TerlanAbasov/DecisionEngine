package com.quant.finance.decision.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.finance.decision.domain.JobKind;
import com.quant.finance.decision.domain.JobStatus;
import com.quant.finance.decision.entity.BacktestJobEntity;
import com.quant.finance.decision.dto.Dtos.JobDto;
import com.quant.finance.decision.error.JobConflictException;
import com.quant.finance.decision.repository.BacktestJobRepository;
import com.quant.finance.decision.service.JsonCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BacktestJobServiceTest {

    private BacktestJobRepository repo;
    private JsonCodec json;
    private ExecutorService executor;
    private BacktestJobService service;

    @BeforeEach
    void setUp() {
        repo = mock(BacktestJobRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        json = new JsonCodec(new ObjectMapper());
        executor = Executors.newSingleThreadExecutor();
        service = new BacktestJobService(repo, json, executor);
    }

    @AfterEach
    void tearDown() { executor.shutdownNow(); }

    private JobDto awaitTerminal(String id) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            JobDto d = service.get(id);
            if (!d.status().equals("RUNNING")) return d;
            Thread.sleep(10);
        }
        fail("job did not finish");
        return null;
    }

    @Test
    void aSuccessfulJobCompletesWithItsResultAndIsStored() throws Exception {
        JobDto started = service.submit(JobKind.RUN, "demo", Map.of("a", 1), p -> Map.of("runId", 7));
        assertEquals("RUNNING".equals(started.status()) || "COMPLETED".equals(started.status()), true);
        assertNull(started.result(), "a job that has only just started carries no result");

        JobDto done = awaitTerminal(started.id());
        assertEquals("COMPLETED", done.status());
        assertEquals(Map.of("runId", 7), done.result());
        assertEquals(100, done.percent());
        assertNotNull(done.finishedAt());
        assertNull(done.error());

        ArgumentCaptor<BacktestJobEntity> saved = ArgumentCaptor.forClass(BacktestJobEntity.class);
        verify(repo, timeout(5000).atLeast(2)).save(saved.capture());
        BacktestJobEntity last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertEquals("COMPLETED", last.getStatus());
        assertEquals("{\"runId\":7}", last.getResultJson());
        assertEquals("{\"a\":1}", saved.getAllValues().get(0).getRequestJson(), "the request is stored at the start");
        assertEquals("demo", last.getTitle());
    }

    @Test
    void onlyOneJobRunsAtATimeAndTheConflictCarriesTheActiveOne() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        JobDto first = service.submit(JobKind.RUN_ALL, "first", null, p -> { await(release); return "one"; });

        JobConflictException conflict = assertThrows(JobConflictException.class,
                () -> service.submit(JobKind.RUN, "second", null, p -> "two"));
        assertEquals(first.id(), conflict.active().id());
        assertEquals("RUNNING", conflict.active().status());
        assertEquals(List.of(first.id()), service.active().stream().map(JobDto::id).toList());

        release.countDown();
        assertEquals("COMPLETED", awaitTerminal(first.id()).status());
        assertTrue(service.active().isEmpty());
        JobDto next = service.submit(JobKind.RUN, "third", null, p -> "three");   // admitted again
        assertEquals("three", awaitTerminal(next.id()).result());
    }

    @Test
    void anInputErrorFailsTheJobWithItsMessage() throws Exception {
        JobDto j = service.submit(JobKind.RUN_ALL, "x", null, p -> { throw new IllegalStateException("No enabled strategies"); });
        JobDto done = awaitTerminal(j.id());
        assertEquals("FAILED", done.status());
        assertEquals("No enabled strategies", done.error());
        assertNull(done.result());
        ArgumentCaptor<BacktestJobEntity> saved = ArgumentCaptor.forClass(BacktestJobEntity.class);
        verify(repo, timeout(5000).atLeast(2)).save(saved.capture());
        assertEquals("FAILED", saved.getAllValues().get(saved.getAllValues().size() - 1).getStatus());
    }

    @Test
    void anErrorThrownInsideThePoolIsUnwrappedAndAnUnexpectedOneNamesItsType() throws Exception {
        JobDto a = service.submit(JobKind.RUN, "a", null,
                p -> { throw new CompletionException(new IllegalArgumentException("bad symbol")); });
        assertEquals("bad symbol", awaitTerminal(a.id()).error());
        JobDto b = service.submit(JobKind.RUN, "b", null, p -> { throw new ArithmeticException("/ by zero"); });
        assertEquals("ArithmeticException: / by zero", awaitTerminal(b.id()).error());
    }

    @Test
    void aJobThatDiesOfAnErrorStillEndsFailedRatherThanStayingRunning() throws Exception {
        JobDto j = service.submit(JobKind.RUN, "oom", null, p -> { throw new OutOfMemoryError("heap"); });
        JobDto done = awaitTerminal(j.id());
        assertEquals("FAILED", done.status());
        assertTrue(done.error().startsWith("Out of memory"));
        assertTrue(service.active().isEmpty());
    }

    @Test
    void cancellingStopsAtTheNextSafePointAndEndsCancelled() throws Exception {
        CountDownLatch working = new CountDownLatch(1);
        JobDto j = service.submit(JobKind.RUN_ALL, "long", null, p -> {
            working.countDown();
            while (true) { p.checkCancelled(); pause(5); }
        });
        assertTrue(working.await(5, TimeUnit.SECONDS));
        JobDto asked = service.cancel(j.id());
        assertTrue(asked.cancelRequested() || asked.status().equals("CANCELLED"));
        JobDto done = awaitTerminal(j.id());
        assertEquals("CANCELLED", done.status());
        assertNull(done.error());
        assertNull(done.result());
        assertFalse(done.cancelRequested());
        assertEquals("CANCELLED", service.cancel(j.id()).status(), "cancelling a finished job just returns it");
    }

    @Test
    void aFinishedJobIsReadFromTheDatabaseOnceItIsNotInMemory() {
        BacktestJobEntity e = new BacktestJobEntity();
        e.setId("abc"); e.setKind("RUN_ALL"); e.setStatus("COMPLETED"); e.setTitle("t");
        e.setCreatedAt(Instant.parse("2026-09-20T10:00:00Z"));
        e.setStartedAt(Instant.parse("2026-09-20T10:00:01Z"));
        e.setFinishedAt(Instant.parse("2026-09-20T10:00:11Z"));
        e.setResultJson("[{\"runId\":5,\"strategy\":\"s\"}]");
        JobProgress done = new JobProgress(JobKind.RUN_ALL);
        e.setProgressJson(json.write(done.finalSnapshot(JobStatus.COMPLETED)));
        when(repo.findById("abc")).thenReturn(Optional.of(e));

        JobDto d = service.get("abc");
        assertEquals("COMPLETED", d.status());
        assertEquals(100, d.percent());
        assertEquals(10_000, d.elapsedMs());
        assertEquals(4, d.steps().size());
        assertEquals(List.of(Map.of("runId", 5, "strategy", "s")), d.result());
    }

    @Test
    void aFailedStoredJobShowsItsErrorAndNoResult() {
        BacktestJobEntity e = new BacktestJobEntity();
        e.setId("f"); e.setKind("RUN"); e.setStatus("FAILED"); e.setError("boom"); e.setResultJson("{\"x\":1}");
        e.setCreatedAt(Instant.now());
        when(repo.findById("f")).thenReturn(Optional.of(e));
        JobDto d = service.get("f");
        assertEquals("boom", d.error());
        assertNull(d.result());
        assertTrue(d.steps().isEmpty(), "no stored snapshot -> no steps, but no crash either");
    }

    @Test
    void anUnknownJobIsNotFound() {
        when(repo.findById("nope")).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> service.get("nope"));
        assertThrows(NoSuchElementException.class, () -> service.cancel("nope"));
    }

    @Test
    void aDatabaseFailureNeverFailsOrLosesTheJob() throws Exception {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        JobDto j = service.submit(JobKind.RUN, "x", null, p -> "fine");
        JobDto done = awaitTerminal(j.id());
        assertEquals("COMPLETED", done.status());
        assertEquals("fine", done.result(), "still readable from memory");
    }

    @Test
    void progressReportedByTheWorkIsVisibleWhileItRuns() throws Exception {
        CountDownLatch reported = new CountDownLatch(1), release = new CountDownLatch(1);
        JobDto j = service.submit(JobKind.RUN_ALL, "p", null, p -> {
            p.planStrategies(List.of("s1", "s2"));
            p.begin(JobProgress.Step.COMPUTE, 4, "half");
            p.advance(JobProgress.Step.COMPUTE, null); p.advance(JobProgress.Step.COMPUTE, null);
            p.strategyStarted("s1");
            reported.countDown();
            await(release);
            return "ok";
        });
        assertTrue(reported.await(5, TimeUnit.SECONDS));
        JobDto live = service.get(j.id());
        assertEquals("RUNNING", live.status());
        assertEquals(7, live.percent());                 // half of the 15% the compute step is worth
        assertEquals(List.of("s1"), live.running());
        assertEquals(2, live.steps().get(2).done());
        release.countDown();
        assertEquals(100, awaitTerminal(j.id()).percent());
    }

    @Test
    void jobsLeftRunningByARestartAreMarkedFailedAndOldOnesPruned() {
        BacktestJobEntity orphan = new BacktestJobEntity();
        orphan.setId("o"); orphan.setKind("RUN"); orphan.setStatus("RUNNING"); orphan.setCreatedAt(Instant.now());
        when(repo.findByStatus("RUNNING")).thenReturn(List.of(orphan));
        when(repo.deleteByCreatedAtBefore(any())).thenReturn(3L);

        service.recoverOnStartup();

        assertEquals("FAILED", orphan.getStatus());
        assertEquals(BacktestJobService.INTERRUPTED, orphan.getError());
        assertNotNull(orphan.getFinishedAt());
        verify(repo).saveAll(List.of(orphan));
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repo).deleteByCreatedAtBefore(cutoff.capture());
        assertTrue(cutoff.getValue().isBefore(Instant.now().minusSeconds(13L * 24 * 3600)));
    }

    @Test
    void startupCleanupNeverBlocksStartingTheApp() {
        when(repo.findByStatus(any())).thenThrow(new RuntimeException("db not ready"));
        assertDoesNotThrow(() -> service.recoverOnStartup());
    }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); }
    }

    private static void await(CountDownLatch l) {
        try { assertTrue(l.await(10, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
    }
}
