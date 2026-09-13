package com.quant.finance.decision.data;

import com.quant.finance.decision.domain.PriceBarEntity;

import java.time.Instant;
import java.util.List;

/** Source of historical bars. One implementation is active per profile. */
public interface MarketDataClient {
    List<PriceBarEntity> fetchHistory(String symbol);

    /**
     * Bars from {@code since} to now — a cheap incremental fetch for periodic polling, so a
     * poll doesn't re-download the full history every tick. Implementations that can't bound
     * the request may fall back to {@link #fetchHistory(String)}.
     */
    default List<PriceBarEntity> fetchHistory(String symbol, Instant since) {
        return fetchHistory(symbol);
    }

    String source();

    /** Bar interval this client is currently configured to fetch (e.g. "1Day", "1Min"). */
    default String configuredTimeframe() { return "1Day"; }
}
