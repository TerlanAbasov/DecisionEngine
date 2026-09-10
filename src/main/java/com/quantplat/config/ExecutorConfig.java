package com.quantplat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Fixed thread pool that fans out CPU-bound backtest work — one task per strategy
 * (run-all), per symbol (single run), per grid cell (optimiser) or per leg (ensemble).
 * Only the pure compute runs here; all persistence stays on the calling transaction thread.
 */
@Configuration
public class ExecutorConfig {

    public static final String BACKTEST_EXECUTOR = "backtestExecutor";

    @Bean(name = BACKTEST_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor backtestExecutor(@Value("${quantplat.backtest.threads:50}") int threads) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(threads);
        ex.setMaxPoolSize(threads);
        ex.setQueueCapacity(100_000);
        ex.setThreadNamePrefix("bt-");
        ex.setAllowCoreThreadTimeOut(true);
        ex.setKeepAliveSeconds(60);
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return ex;
    }
}
