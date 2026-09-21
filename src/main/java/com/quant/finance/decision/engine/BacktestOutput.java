package com.quant.finance.decision.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class BacktestOutput {
    public final String strategy;
    public final List<String> symbols;
    public final Instant startDate, endDate;
    public final Instant[] dates;
    public final double[] equity;        // strategy equity curve
    public final double[] benchmark;     // buy & hold equity curve
    public final double[] drawdown;      // fraction, <= 0
    public final List<TradeResult> trades;
    public final Map<String, Double> metrics;
    /**
     * Each symbol's own total return %, computed like the portfolio's {@code metrics.totalReturnPct} on its own return stream alone;
     * empty when a per-symbol breakdown isn't meaningful (e.g. a pairs run).
     */
    public final Map<String, Double> symbolReturnsPct;
    /** Full standalone result per symbol (metrics + calendar-year returns); empty for pairs runs. */
    public final Map<String, SymbolResult> symbolResults;
    /** The blended portfolio's return in each calendar year (UTC), in percent. */
    public final Map<String, Double> yearlyReturnsPct;

    public BacktestOutput(String strategy, List<String> symbols, Instant[] dates,
                          double[] equity, double[] benchmark, double[] drawdown,
                          List<TradeResult> trades, Map<String, Double> metrics,
                          Map<String, Double> symbolReturnsPct,
                          Map<String, SymbolResult> symbolResults,
                          Map<String, Double> yearlyReturnsPct) {
        this.strategy = strategy;
        this.symbols = symbols;
        this.dates = dates;
        this.startDate = dates.length > 0 ? dates[0] : null;
        this.endDate = dates.length > 0 ? dates[dates.length - 1] : null;
        this.equity = equity;
        this.benchmark = benchmark;
        this.drawdown = drawdown;
        this.trades = trades;
        this.metrics = metrics;
        this.symbolReturnsPct = symbolReturnsPct;
        this.symbolResults = symbolResults;
        this.yearlyReturnsPct = yearlyReturnsPct;
    }
}
