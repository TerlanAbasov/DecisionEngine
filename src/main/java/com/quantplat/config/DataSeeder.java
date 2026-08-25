package com.quantplat.config;

import com.quantplat.service.StrategyService;
import com.quantplat.service.UniverseService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Seeds the strategy catalog and default universe into the database on startup. */
@Component
@Order(1)
public class DataSeeder implements CommandLineRunner {

    private final StrategyService strategyService;
    private final UniverseService universeService;

    public DataSeeder(StrategyService strategyService, UniverseService universeService) {
        this.strategyService = strategyService;
        this.universeService = universeService;
    }

    @Override
    public void run(String... args) {
        strategyService.seed();
        universeService.seedDefaults();
    }
}
