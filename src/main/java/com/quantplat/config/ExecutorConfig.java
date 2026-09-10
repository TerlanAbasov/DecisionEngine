package com.quantplat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed {@link ExecutorService} that fans out CPU-bound backtest work — one task per
 * strategy (run-all), per symbol (single run), per grid cell (optimiser) or per leg
 * (ensemble). Only the pure compute runs here; all persistence stays on the calling
 * transaction thread. Spring calls {@code shutdown()} on context close.
 */
@Configuration
public class ExecutorConfig {

    public static final String BACKTEST_EXECUTOR = "backtestExecutor";

    @Bean(name = BACKTEST_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService backtestExecutor(@Value("${quantplat.backtest.threads:50}") int threads) {
        int n = Math.max(1, threads);
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "bt-" + seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(n, tf);
    }
}
