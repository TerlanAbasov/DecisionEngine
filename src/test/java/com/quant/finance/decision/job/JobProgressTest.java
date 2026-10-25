package com.quant.finance.decision.job;

import static org.junit.jupiter.api.Assertions.*;

import com.quant.finance.decision.domain.JobKind;
import com.quant.finance.decision.domain.JobStatus;
import com.quant.finance.decision.dto.Dtos.JobItemDto;
import com.quant.finance.decision.dto.Dtos.JobStepDto;
import com.quant.finance.decision.error.JobCancelledException;
import com.quant.finance.decision.job.JobProgress.Snapshot;
import com.quant.finance.decision.job.JobProgress.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JobProgressTest {

    private static JobStepDto step(Snapshot snapshot, Step step) {
        return snapshot.steps().get(step.ordinal());
    }

    private static JobItemDto item(List<JobItemDto> items, String name) {
        return items.stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aFreshJobIsZeroPercentWithFourPendingSteps() {
        Snapshot snapshot = new JobProgress(JobKind.RUN_ALL).snapshot();
        assertEquals(0, snapshot.percent());
        assertEquals(
                List.of("prepare", "load", "compute", "save"),
                snapshot.steps().stream().map(JobStepDto::key).toList());
        assertTrue(snapshot.steps().stream().allMatch(x -> x.state().equals("pending")));
        assertEquals("Run strategies", step(snapshot, Step.COMPUTE).label());
        assertTrue(snapshot.symbols().isEmpty()
                && snapshot.strategies().isEmpty()
                && snapshot.running().isEmpty());
    }

    @Test
    void percentIsTheWeightedMeanOfTheSteps() {
        JobProgress progress = new JobProgress(JobKind.RUN_ALL); // weights 2 / 8 / 15 / 75
        progress.begin(Step.PREPARE, 1, "x");
        progress.complete(Step.PREPARE);
        assertEquals(2, progress.snapshot().percent());
        progress.begin(Step.LOAD, 4, "x");
        progress.advance(Step.LOAD, null);
        progress.advance(Step.LOAD, null); // 2 of 4 of 8 = 4
        assertEquals(6, progress.snapshot().percent());
        progress.complete(Step.LOAD);
        assertEquals(10, progress.snapshot().percent());
        progress.beginCompute(10, 5, "x");
        for (int i = 0; i < 5; i++) {
            progress.symbolComputed("A"); // half of 15 = 7.5
        }
        assertEquals(17, progress.snapshot().percent()); // floor(17.5)
        progress.begin(Step.SAVE, 2, "x");
        progress.advance(Step.SAVE, "one"); // half of 75 = 37.5
        assertEquals(55, progress.snapshot().percent());
        assertEquals("one", step(progress.snapshot(), Step.SAVE).detail());
    }

    @Test
    void everyKindsWeightsAddUpToOneHundredSoAFinishedJobReadsExactlyOneHundred() {
        for (JobKind kind : JobKind.values()) {
            JobProgress progress = new JobProgress(kind);
            for (Step s : Step.values()) {
                progress.begin(s, 1, "x");
                progress.complete(s);
            }
            assertEquals(100, progress.snapshot().percent(), kind + " must reach 100 when every step is done");
        }
    }

    @Test
    void stepsMayOverlapAndAnOverrunNeverExceedsTheTotal() {
        JobProgress progress = new JobProgress(JobKind.RUN);
        progress.begin(Step.COMPUTE, 2, "x");
        progress.begin(Step.SAVE, 2, "x");
        for (int i = 0; i < 5; i++) {
            progress.advance(Step.COMPUTE, null);
        }
        Snapshot snapshot = progress.snapshot();
        assertEquals("active", step(snapshot, Step.COMPUTE).state());
        assertEquals("active", step(snapshot, Step.SAVE).state());
        assertEquals(2, step(snapshot, Step.COMPUTE).done(), "done is clamped to total");
        assertTrue(snapshot.percent() >= 0 && snapshot.percent() < 100);
    }

    @Test
    void aStepWithNoKnownTotalContributesNothingUntilItIsCompleted() {
        JobProgress progress = new JobProgress(JobKind.PAIRS);
        progress.begin(Step.COMPUTE, 0, "x");
        assertEquals(0, progress.snapshot().percent());
        progress.complete(Step.COMPUTE);
        assertEquals(30, progress.snapshot().percent());
    }

    @Test
    void symbolsTrackLoadingAndPerSymbolComputeProgress() {
        JobProgress progress = new JobProgress(JobKind.RUN_ALL);
        progress.planSymbols(List.of("AAA", "BBB", "CCC"));
        progress.begin(Step.LOAD, 3, "Loading");
        assertTrue(
                progress.snapshot().symbols().stream().allMatch(i -> i.state().equals("pending")));
        progress.symbolLoaded("AAA", true);
        progress.symbolLoaded("BBB", false);
        progress.symbolLoaded("CCC", true);
        Snapshot snapshot = progress.snapshot();
        assertEquals("done", item(snapshot.symbols(), "AAA").state());
        assertEquals("nodata", item(snapshot.symbols(), "BBB").state());
        assertEquals("CCC (3/3)", step(snapshot, Step.LOAD).detail());

        progress.beginCompute(6, 3, "x"); // 3 strategies over the 2 loaded symbols
        progress.symbolComputed("AAA");
        progress.symbolComputed("AAA");
        progress.symbolComputed("CCC");
        progress.symbolComputed("UNKNOWN"); // ignored per symbol, still counted as work done
        snapshot = progress.snapshot();
        assertEquals(2, item(snapshot.symbols(), "AAA").done());
        assertEquals(3, item(snapshot.symbols(), "AAA").total());
        assertEquals(1, item(snapshot.symbols(), "CCC").done());
        assertEquals(0, item(snapshot.symbols(), "BBB").total(), "a symbol without data is never computed");
        assertEquals(4, step(snapshot, Step.COMPUTE).done());
    }

    @Test
    void strategiesMoveThroughPendingRunningDoneOrFailed() {
        JobProgress progress = new JobProgress(JobKind.RUN_ALL);
        progress.planStrategies(List.of("s1", "s2", "s3"));
        progress.strategyStarted("s1");
        progress.strategyStarted("s2");
        Snapshot snapshot = progress.snapshot();
        assertEquals(List.of("s1", "s2"), snapshot.running());
        assertEquals("pending", item(snapshot.strategies(), "s3").state());
        progress.strategyFinished("s1", true);
        progress.strategyFinished("s2", false);
        snapshot = progress.snapshot();
        assertEquals("done", item(snapshot.strategies(), "s1").state());
        assertEquals("failed", item(snapshot.strategies(), "s2").state());
        assertTrue(snapshot.running().isEmpty());
        assertEquals(
                List.of("s1", "s2", "s3"),
                snapshot.strategies().stream().map(JobItemDto::name).toList(),
                "order kept");
    }

    @Test
    void cancellingMakesCheckCancelledThrow() {
        JobProgress progress = new JobProgress(JobKind.RUN);
        assertDoesNotThrow(progress::checkCancelled);
        assertFalse(progress.isCancelled());
        progress.requestCancel();
        assertTrue(progress.isCancelled());
        assertThrows(JobCancelledException.class, progress::checkCancelled);
    }

    @Test
    void theFinalSnapshotReflectsHowTheJobEnded() {
        for (JobStatus outcome : List.of(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED)) {
            JobProgress progress = new JobProgress(JobKind.RUN_ALL);
            progress.planStrategies(List.of("a", "b", "c"));
            progress.begin(Step.COMPUTE, 3, "x");
            progress.strategyStarted("a");
            progress.strategyFinished("a", true);
            progress.strategyStarted("b");
            Snapshot snapshot = progress.finalSnapshot(outcome);
            assertTrue(snapshot.running().isEmpty());
            switch (outcome) {
                case COMPLETED -> {
                    assertEquals(100, snapshot.percent());
                    assertTrue(snapshot.steps().stream().allMatch(x -> x.state().equals("done")));
                    assertEquals("done", item(snapshot.strategies(), "b").state());
                }
                case FAILED -> {
                    assertEquals(
                            "failed", item(snapshot.strategies(), "b").state(), "the strategy that was running failed");
                    assertEquals("pending", item(snapshot.strategies(), "c").state());
                    assertEquals("active", step(snapshot, Step.COMPUTE).state());
                }
                default ->
                    assertEquals(
                            "pending", item(snapshot.strategies(), "b").state(), "a cancelled strategy did not finish");
            }
        }
    }

    @Test
    void concurrentUpdatesFromManyThreadsAreAllCounted() throws Exception {
        JobProgress progress = new JobProgress(JobKind.RUN_ALL);
        List<String> syms = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            syms.add("S" + i);
        }
        progress.planSymbols(syms);
        for (String s : syms) {
            progress.symbolLoaded(s, true);
        }
        int threads = 8;
        int perThread = 2000;
        progress.beginCompute(threads * perThread, threads * perThread / 4, "x");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < perThread; i++) {
                    progress.symbolComputed("S" + (i % 4));
                    if (i % 100 == 0) {
                        progress.snapshot(); // readers run alongside the writers
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (var f : fs) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();
        Snapshot snapshot = progress.snapshot();
        assertEquals(threads * perThread, step(snapshot, Step.COMPUTE).done());
        for (String sym : syms) {
            assertEquals(threads * perThread / 4, item(snapshot.symbols(), sym).done());
        }
    }
}
