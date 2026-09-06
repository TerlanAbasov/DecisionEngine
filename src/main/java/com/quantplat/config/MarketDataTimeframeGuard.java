package com.quantplat.config;

import com.quantplat.data.MarketDataClient;
import com.quantplat.repository.PriceBarRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * On boot, drop any cached bars whose interval differs from {@code quantplat.alpaca.timeframe}
 * (legacy rows with no recorded timeframe included). Without this, changing the config from
 * e.g. 1Day to 1Min has no effect — the app keeps serving the previously cached daily bars.
 * Idempotent: deletes 0 rows once the cache is consistent.
 */
@Component
@Order(0)
public class MarketDataTimeframeGuard implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketDataTimeframeGuard.class);

    private final PriceBarRepository repo;
    private final MarketDataClient client;

    public MarketDataTimeframeGuard(PriceBarRepository repo, MarketDataClient client) {
        this.repo = repo;
        this.client = client;
    }

    @Override
    @Transactional
    public void run(String... args) {
        String tf = client.configuredTimeframe();
        int dropped = repo.deleteByTimeframeNot(tf);
        if (dropped > 0)
            log.warn("Cleared {} cached price bars fetched at a different interval; they will refetch at '{}'.",
                    dropped, tf);
    }
}
