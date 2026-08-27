package com.quantplat.engine;

import java.time.Instant;

public final class TradeResult {
    public final String symbol;
    public final String side;         // LONG | SHORT
    public final Instant entryDate, exitDate;
    public final double entryPx, exitPx;
    public final int bars;
    public final double returnPct;    // fraction

    public TradeResult(String symbol, String side, Instant entryDate, Instant exitDate,
                       double entryPx, double exitPx, int bars, double returnPct) {
        this.symbol = symbol;
        this.side = side;
        this.entryDate = entryDate;
        this.exitDate = exitDate;
        this.entryPx = entryPx;
        this.exitPx = exitPx;
        this.bars = bars;
        this.returnPct = returnPct;
    }
}
