package com.quant.finance.decision.live;

import com.quant.finance.decision.config.ExecutorConfig;
import com.quant.finance.decision.service.StrategyService;
import com.quant.finance.decision.service.UniverseService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ExecutorService;

@Configuration
public class LiveTradingConfig {

    @Bean
    public Clock liveClock() {
        return Clock.systemUTC();
    }

    @Bean
    public LiveTradingEngine liveTradingEngine(TradingGateway gateway, LiveDataSource data, StrategyService strategies,
                                               UniverseService universe, LiveStore store, Clock clock,
                                               @Qualifier(ExecutorConfig.BACKTEST_EXECUTOR) ExecutorService pool) {
        return new LiveTradingEngine(gateway, data, strategies, universe, store, clock, pool);
    }
}
