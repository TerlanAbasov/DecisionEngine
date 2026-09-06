package com.quantplat.engine;

/** Immutable knob-set for one backtest. Build via {@link #builder()}. */
public final class BacktestConfig {
    public final double capital;
    public final double commissionBps;   // per side, bps of notional
    public final double slippageBps;     // per side
    public final boolean allowShort;
    public final int execLag;            // bars between signal and fill (>=1 avoids look-ahead)

    public final double positionSize;    // gross exposure multiplier applied to every target position
    public final double riskFreePct;     // annual risk-free rate, % — Sharpe/Sortino use excess return
    public final int warmupBars;         // leading bars whose P&L is ignored (indicator burn-in)
    public final double stopLossPct;     // force-exit an open trade once its loss reaches this % (0 = off)
    public final double takeProfitPct;   // force-exit an open trade once its gain reaches this % (0 = off)
    public final Timeframe timeframe;    // resample stored bars up to this frame before running

    private BacktestConfig(Builder b) {
        this.capital = b.capital;
        this.commissionBps = b.commissionBps;
        this.slippageBps = b.slippageBps;
        this.allowShort = b.allowShort;
        this.execLag = Math.max(0, b.execLag);
        this.positionSize = clamp(b.positionSize, 0.1, 5.0);
        this.riskFreePct = b.riskFreePct;
        this.warmupBars = Math.max(0, b.warmupBars);
        this.stopLossPct = Math.max(0, b.stopLossPct);
        this.takeProfitPct = Math.max(0, b.takeProfitPct);
        this.timeframe = b.timeframe == null ? Timeframe.NATIVE : b.timeframe;
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }

    public static BacktestConfig defaults() { return builder().build(); }

    public static Builder builder() { return new Builder(); }

    /** Copy of this config with a different resample timeframe (for per-strategy runs). */
    public BacktestConfig withTimeframe(Timeframe tf) {
        return builder()
                .capital(capital).commissionBps(commissionBps).slippageBps(slippageBps)
                .allowShort(allowShort).execLag(execLag).positionSize(positionSize)
                .riskFreePct(riskFreePct).warmupBars(warmupBars)
                .stopLossPct(stopLossPct).takeProfitPct(takeProfitPct)
                .timeframe(tf == null ? Timeframe.NATIVE : tf)
                .build();
    }

    public static final class Builder {
        private double capital = 100_000;
        private double commissionBps = 1.0;
        private double slippageBps = 2.0;
        private boolean allowShort = true;
        private int execLag = 1;
        private double positionSize = 1.0;
        private double riskFreePct = 0.0;
        private int warmupBars = 0;
        private double stopLossPct = 0.0;
        private double takeProfitPct = 0.0;
        private Timeframe timeframe = Timeframe.NATIVE;

        public Builder capital(double v)        { this.capital = v; return this; }
        public Builder commissionBps(double v)  { this.commissionBps = v; return this; }
        public Builder slippageBps(double v)    { this.slippageBps = v; return this; }
        public Builder allowShort(boolean v)    { this.allowShort = v; return this; }
        public Builder execLag(int v)           { this.execLag = v; return this; }
        public Builder positionSize(double v)   { this.positionSize = v; return this; }
        public Builder riskFreePct(double v)    { this.riskFreePct = v; return this; }
        public Builder warmupBars(int v)        { this.warmupBars = v; return this; }
        public Builder stopLossPct(double v)    { this.stopLossPct = v; return this; }
        public Builder takeProfitPct(double v)  { this.takeProfitPct = v; return this; }
        public Builder timeframe(Timeframe v)   { this.timeframe = v; return this; }

        public BacktestConfig build() { return new BacktestConfig(this); }
    }
}
