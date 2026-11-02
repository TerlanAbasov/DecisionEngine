package com.quant.finance.decision.config;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fixed pool for CPU-bound backtest work — one task per strategy, symbol, grid cell or ensemble leg.
 * Only pure compute runs here; persistence stays on the calling transaction thread. Shut down on context close.
 */
@Configuration
@RequiredArgsConstructor
public class ExecutorConfig {

    public static final String BACKTEST_EXECUTOR = "backtestExecutor";
    public static final String BACKTEST_JOB_EXECUTOR = "backtestJobExecutor";

    /** Captures the submitting thread's context (trace id, span) so pool threads log under the same trace. */
    private static final ContextSnapshotFactory CONTEXT_SNAPSHOTS =
            ContextSnapshotFactory.builder().build();

    private final ApplicationProperties properties;

    /**
     * Runs one backtest job at a time (orchestration, transaction, persistence); the compute fans out onto {@link #BACKTEST_EXECUTOR}.
     * A second job thread would only fight the first for CPU and heap, and the job service admits a single active job anyway.
     */
    @Bean(name = BACKTEST_JOB_EXECUTOR, destroyMethod = "shutdownNow")
    public ExecutorService backtestJobExecutor() {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "bt-job");
            thread.setDaemon(true);
            return thread;
        });
        return ContextExecutorService.wrap(executor, CONTEXT_SNAPSHOTS);
    }

    @Bean(name = BACKTEST_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService backtestExecutor() {
        int threadCount = Math.max(1, properties.getBacktest().getThreads());
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "bt-" + seq.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(threadCount, threadFactory);
        return ContextExecutorService.wrap(executor, CONTEXT_SNAPSHOTS);
    }
}
