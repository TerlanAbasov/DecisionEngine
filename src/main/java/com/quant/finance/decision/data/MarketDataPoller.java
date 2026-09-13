package com.quant.finance.decision.data;

import com.quant.finance.decision.service.ScannerService;
import com.quant.finance.decision.service.UniverseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodically pulls fresh bars for the universe from the active {@link MarketDataClient}
 * (e.g. Alpaca) and re-runs the strategy scan, so signals stay current without a manual
 * /api/scan call. One symbol failing to fetch doesn't stop the rest.
 */
@Component
@ConditionalOnProperty(name = "quantplat.poll.enabled", havingValue = "true", matchIfMissing = true)
public class MarketDataPoller {

    private static final Logger log = LoggerFactory.getLogger(MarketDataPoller.class);

    private final MarketDataService marketData;
    private final UniverseService universe;
    private final ScannerService scanner;

    public MarketDataPoller(MarketDataService marketData, UniverseService universe, ScannerService scanner) {
        this.marketData = marketData;
        this.universe = universe;
        this.scanner = scanner;
    }

    @Scheduled(initialDelayString = "${quantplat.poll.initial-delay-ms:10000}",
               fixedDelayString = "${quantplat.poll.interval-ms:900000}")
    public void pollAndDecide() {
        List<String> symbols = universe.get();
        if (symbols.isEmpty()) return;

        int refreshed = 0, failed = 0;
        for (String symbol : symbols) {
            try {
                marketData.pollLatest(symbol);
                refreshed++;
            } catch (Exception e) {
                failed++;
                log.warn("Poll: failed to refresh {} from {}: {}", symbol, marketData.activeSource(), e.getMessage());
            }
        }
        if (refreshed == 0) {
            log.warn("Poll: no symbols refreshed successfully, skipping scan");
            return;
        }

        var signals = scanner.scan(null, false);
        log.info("Poll complete ({}): {}/{} symbols refreshed, {} failed, {} active signals",
                marketData.activeSource(), refreshed, symbols.size(), failed, signals.size());
    }
}
