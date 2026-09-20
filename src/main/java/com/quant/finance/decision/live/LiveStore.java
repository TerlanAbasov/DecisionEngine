package com.quant.finance.decision.live;

import com.quant.finance.decision.domain.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Where the paper-trading job keeps its state. Backed by the database; an in-memory copy is used in tests. */
public interface LiveStore {

    static String key(String strategy, String symbol) { return strategy + "|" + symbol; }

    /** Inserts or updates; returns the saved row (with its id). */
    LiveCycleEntity saveCycle(LiveCycleEntity cycle);

    /** Every virtual position, by {@link #key}. */
    Map<String, LiveSlotEntity> slots();

    /** Saves slot changes and the trades they closed together — all or nothing. */
    void commit(List<LiveSlotEntity> slots, List<LiveTradeEntity> trades);

    LiveOrderEntity saveOrder(LiveOrderEntity order);

    void saveEquity(LiveEquityEntity equity);

    void saveStrategyPnl(List<LiveStrategyPnlEntity> rows);

    /** Drops the equity and per-strategy curves older than {@code before}. */
    void pruneCurves(Instant before);
}
