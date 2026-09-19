package com.quant.finance.decision.engine;

import java.time.Instant;

/**
 * One round trip in one symbol.
 *
 * <p>All return fields are fractions of the run's starting capital, in the per-symbol
 * <em>standalone</em> view (the symbol traded alone with the full capital), except
 * {@link #returnPct}, which is the trade's contribution to the blended portfolio
 * (each bar's P&L scaled by that bar's blend weight). For a single-symbol run the two are the same.
 *
 * <p>Prices are the closes at which the equity accounting actually opens and closes the
 * position, so the trade log reconciles with the equity curve: the {@link #netReturn}s of a
 * symbol's trades sum to that symbol's total return.
 */
public final class TradeResult {
    public final String symbol;
    public final String side;         // LONG | SHORT
    public final Instant entryDate, exitDate;
    public final double entryPx, exitPx;
    public final int bars;            // bars the position was held
    public final double returnPct;    // fraction; portfolio contribution (blend-weighted net)

    public final double grossReturn;  // fraction; price P&L before costs (sum of bar returns while held)
    public final double cost;         // fraction; commission + slippage charged to this trade (>= 0)
    public final double netReturn;    // fraction; grossReturn - cost
    public final double exposure;     // fraction of capital committed at entry (|position|)
    public final boolean open;        // still open at the last bar: marked to market, no exit cost yet

    public TradeResult(String symbol, String side, Instant entryDate, Instant exitDate,
                       double entryPx, double exitPx, int bars, double returnPct,
                       double grossReturn, double cost, double exposure, boolean open) {
        this.symbol = symbol;
        this.side = side;
        this.entryDate = entryDate;
        this.exitDate = exitDate;
        this.entryPx = entryPx;
        this.exitPx = exitPx;
        this.bars = bars;
        this.returnPct = returnPct;
        this.grossReturn = grossReturn;
        this.cost = cost;
        this.netReturn = grossReturn - cost;
        this.exposure = exposure;
        this.open = open;
    }
}
