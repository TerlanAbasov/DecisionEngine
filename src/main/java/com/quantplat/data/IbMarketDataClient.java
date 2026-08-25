package com.quantplat.data;

import com.quantplat.domain.PriceBarEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Interactive Brokers data source. Active when quantplat.data-source=ib.
 *
 * <p>This is the seam where you plug in your existing Java IB ExecutionEngine (or a
 * fresh TWS API client). Fetch 5+ years of daily TRADES bars via
 * {@code reqHistoricalData} (durationStr "6 Y", barSize "1 day"), map each bar to a
 * {@link PriceBarEntity} with source "ib", and return the list. The service layer
 * persists them to the {@code price_bar} table, so IB is only hit on first load or
 * an explicit refresh.
 */
@Component
@ConditionalOnProperty(name = "quantplat.data-source", havingValue = "ib")
public class IbMarketDataClient implements MarketDataClient {

    @Value("${quantplat.ib.host:127.0.0.1}")
    private String host;
    @Value("${quantplat.ib.port:7497}")
    private int port;
    @Value("${quantplat.ib.client-id:17}")
    private int clientId;

    @Override
    public List<PriceBarEntity> fetchHistory(String symbol) {
        throw new UnsupportedOperationException(
            "IB data source selected but not wired. Connect your Java IB ExecutionEngine here: "
            + "request historical daily bars for '" + symbol + "' from " + host + ":" + port
            + " (clientId " + clientId + "), map each bar to PriceBarEntity(source=\"ib\"), and return them. "
            + "Until then run with quantplat.data-source=synthetic.");
    }

    @Override
    public String source() {
        return "ib";
    }
}
