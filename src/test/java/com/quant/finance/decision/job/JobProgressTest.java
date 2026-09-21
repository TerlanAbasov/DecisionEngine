package com.quant.finance.decision.job;

import com.quant.finance.decision.domain.JobKind;
import com.quant.finance.decision.domain.JobStatus;
import com.quant.finance.decision.dto.Dtos.JobItemDto;
import com.quant.finance.decision.dto.Dtos.JobStepDto;
import com.quant.finance.decision.error.JobCancelledException;
import com.quant.finance.decision.job.JobProgress.Snapshot;
import com.quant.finance.decision.job.JobProgress.Step;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JobProgressTest {

    private static JobStepDto step(Snapshot s, Step k) { return s.steps().get(k.ordinal()); }

    private static JobItemDto item(List<JobItemDto> l, String name) {
        return l.stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aFreshJobIsZeroPercentWithFourPendingSteps() {
        Snapshot s = new JobProgress(JobKind.RUN_ALL).snapshot();
        assertEquals(0, s.percent());
        assertEquals(List.of("prepare", "load", "compute", "save"), s.steps().stream().map(JobStepDto::key).toList());
        assertTrue(s.steps().stream().allMatch(x -> x.state().equals("pending")));
        assertEquals("Run strategies", step(s, Step.COMPUTE).label());
        assertTrue(s.symbols().isEmpty() && s.strategies().isEmpty() && s.running().isEmpty());
    }

    @Test
    void percentIsTheWeightedMeanOfTheSteps() {
        JobProgress p = new JobProgress(JobKind.RUN_ALL);          // weights 2 / 8 / 15 / 75
        p.begin(Step.PREPARE, 1, "x"); p.complete(Step.PREPARE);
        assertEquals(2, p.snapshot().percent());
        p.begin(Step.LOAD, 4, "x");
        p.advance(Step.LOAD, null); p.advance(Step.LOAD, null);    // 2 of 4 of 8 = 4
        assertEquals(6, p.snapshot().percent());
        p.complete(Step.LOAD);
        assertEquals(10, p.snapshot().percent());
        p.beginCompute(10, 5, "x");
        for (int i = 0; i < 5; i++) p.symbolComputed("A");         // half of 15 = 7.5
        assertEquals(17, p.snapshot().percent());                  // floor(17.5)
        p.begin(Step.SAVE, 2, "x"); p.advance(Step.SAVE, "one");   // half of 75 = 37.5
        assertEquals(55, p.snapshot().percent());
        assertEquals("one", step(p.snapshot(), Step.SAVE).detail());
    }

    @Test
    void everyKindsWeightsAddUpToOneHundredSoAFinishedJobReadsExactlyOneHundred() {
        for (JobKind kind : JobKind.values()) {
            JobProgress p = new JobProgress(kind);
            for (Step s : Step.values()) { p.begin(s, 1, "x"); p.complete(s); }
            assertEquals(100, p.snapshot().percent(), kind + " must reach 100 when every step is done");
        }
    }

    @Test
    void stepsMayOverlapAndAnOverrunNeverExceedsTheTotal() {
        JobProgress p = new JobProgress(JobKind.RUN);
        p.begin(Step.COMPUTE, 2, "x");
        p.begin(Step.SAVE, 2, "x");
        for (int i = 0; i < 5; i++) p.advance(Step.COMPUTE, null);
        Snapshot s = p.snapshot();
        assertEquals("active", step(s, Step.COMPUTE).state());
        assertEquals("active", step(s, Step.SAVE).state());
        assertEquals(2, step(s, Step.COMPUTE).done(), "done is clamped to total");
        assertTrue(s.percent() >= 0 && s.percent() < 100);
    }

    @Test
    void aStepWithNoKnownTotalContributesNothingUntilItIsCompleted() {
        JobProgress p = new JobProgress(JobKind.PAIRS);
        p.begin(Step.COMPUTE, 0, "x");
        assertEquals(0, p.snapshot().percent());
        p.complete(Step.COMPUTE);
        assertEquals(30, p.snapshot().percent());
    }

    @Test
    void symbolsTrackLoadingAndPerSymbolComputeProgress() {
        JobProgress p = new JobProgress(JobKind.RUN_ALL);
        p.planSymbols(List.of("AAA", "BBB", "CCC"));
        p.begin(Step.LOAD, 3, "Loading");
        assertTrue(p.snapshot().symbols().stream().allMatch(i -> i.state().equals("pending")));
        p.symbolLoaded("AAA", true);
        p.symbolLoaded("BBB", false);
        p.symbolLoaded("CCC", true);
        Snapshot s = p.snapshot();
        assertEquals("done", item(s.symbols(), "AAA").state());
        assertEquals("nodata", item(s.symbols(), "BBB").state());
        assertEquals("CCC (3/3)", step(s, Step.LOAD).detail());

        p.beginCompute(6, 3, "x");                                  // 3 strategies over the 2 loaded symbols
        p.symbolComputed("AAA"); p.symbolComputed("AAA"); p.symbolComputed("CCC");
        p.symbolComputed("UNKNOWN");                                // ignored per symbol, still counted as work done
        s = p.snapshot();
        assertEquals(2, item(s.symbols(), "AAA").done());
        assertEquals(3, item(s.symbols(), "AAA").total());
        assertEquals(1, item(s.symbols(), "CCC").done());
        assertEquals(0, item(s.symbols(), "BBB").total(), "a symbol without data is never computed");
        assertEquals(4, step(s, Step.COMPUTE).done());
    }

    @Test
    void strategiesMoveThroughPendingRunningDoneOrFailed() {
        JobProgress p = new JobProgress(JobKind.RUN_ALL);
        p.planStrategies(List.of("s1", "s2", "s3"));
        p.strategyStarted("s1"); p.strategyStarted("s2");
        Snapshot s = p.snapshot();
        assertEquals(List.of("s1", "s2"), s.running());
        assertEquals("pending", item(s.strategies(), "s3").state());
        p.strategyFinished("s1", true); p.strategyFinished("s2", false);
        s = p.snapshot();
        assertEquals("done", item(s.strategies(), "s1").state());
        assertEquals("failed", item(s.strategies(), "s2").state());
        assertTrue(s.running().isEmpty());
        assertEquals(List.of("s1", "s2", "s3"), s.strategies().stream().map(JobItemDto::name).toList(), "order kept");
    }

    @Test
    void cancellingMakesCheckCancelledThrow() {
        JobProgress p = new JobProgress(JobKind.RUN);
        assertDoesNotThrow(p::checkCancelled);
        assertFalse(p.isCancelled());
        p.requestCancel();
        assertTrue(p.isCancelled());
        assertThrows(JobCancelledException.class, p::checkCancelled);
    }

    @Test
    void theFinalSnapshotReflectsHowTheJobEnded() {
        for (JobStatus outcome : List.of(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED)) {
            JobProgress p = new JobProgress(JobKind.RUN_ALL);
            p.planStrategies(List.of("a", "b", "c"));
            p.begin(Step.COMPUTE, 3, "x");
            p.strategyStarted("a"); p.strategyFinished("a", true); p.strategyStarted("b");
            Snapshot s = p.finalSnapshot(outcome);
            assertTrue(s.running().isEmpty());
            switch (outcome) {
                case COMPLETED -> {
                    assertEquals(100, s.percent());
                    assertTrue(s.steps().stream().allMatch(x -> x.state().equals("done")));
                    assertEquals("done", item(s.strategies(), "b").state());
                }
                case FAILED -> {
                    assertEquals("failed", item(s.strategies(), "b").state(), "the strategy that was running failed");
                    assertEquals("pending", item(s.strategies(), "c").state());
                    assertEquals("active", step(s, Step.COMPUTE).state());
                }
                default -> assertEquals("pending", item(s.strategies(), "b").state(), "a cancelled strategy did not finish");
            }
        }
    }

    @Test
    void concurrentUpdatesFromManyThreadsAreAllCounted() throws Exception {
        JobProgress p = new JobProgress(JobKind.RUN_ALL);
        List<String> syms = new ArrayList<>();
        for (int i = 0; i < 4; i++) syms.add("S" + i);
        p.planSymbols(syms);
        for (String s : syms) p.symbolLoaded(s, true);
        int threads = 8, perThread = 2000;
        p.beginCompute(threads * perThread, threads * perThread / 4, "x");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < perThread; i++) {
                    p.symbolComputed("S" + (i % 4));
                    if (i % 100 == 0) p.snapshot();                 // readers run alongside the writers
                }
                return null;
            }));
        }
        go.countDown();
        for (var f : fs) f.get(20, TimeUnit.SECONDS);
        pool.shutdown();
        Snapshot s = p.snapshot();
        assertEquals(threads * perThread, step(s, Step.COMPUTE).done());
        for (String sym : syms) assertEquals(threads * perThread / 4, item(s.symbols(), sym).done());
    }
}
