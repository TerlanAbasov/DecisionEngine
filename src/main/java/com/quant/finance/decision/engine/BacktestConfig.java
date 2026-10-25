package com.quant.finance.decision.engine;

/** Immutable knob-set for one backtest. Build via {@link #builder()}. */
public final class BacktestConfig {
    public final double capital;
    public final double commissionBps; // per side, bps of notional
    public final double slippageBps; // per side
    public final boolean allowShort;
    public final int execLag; // bars between signal and fill (>=1 avoids look-ahead)

    public final double positionSize; // gross exposure multiplier applied to every target position
    public final double riskFreePct; // annual risk-free rate, % — Sharpe/Sortino use excess return
    public final int warmupBars; // leading bars whose P&L is ignored (indicator burn-in)
    public final double stopLossPct; // force-exit an open trade once its loss reaches this % (0 = off)
    public final double takeProfitPct; // force-exit an open trade once its gain reaches this % (0 = off)
    public final Timeframe timeframe; // resample stored bars up to this frame before running

    private BacktestConfig(Builder builder) {
        this.capital = builder.capital;
        this.commissionBps = builder.commissionBps;
        this.slippageBps = builder.slippageBps;
        this.allowShort = builder.allowShort;
        this.execLag = Math.max(0, builder.execLag);
        this.positionSize = clamp(builder.positionSize, 0.1, 5.0);
        this.riskFreePct = builder.riskFreePct;
        this.warmupBars = Math.max(0, builder.warmupBars);
        this.stopLossPct = Math.max(0, builder.stopLossPct);
        this.takeProfitPct = Math.max(0, builder.takeProfitPct);
        this.timeframe = builder.timeframe == null ? Timeframe.NATIVE : builder.timeframe;
    }

    private static double clamp(double value, double min, double max) {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }

    public static BacktestConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Copy of this config with a different resample timeframe (for per-strategy runs). */
    public BacktestConfig withTimeframe(Timeframe timeframe) {
        return builder()
                .capital(capital)
                .commissionBps(commissionBps)
                .slippageBps(slippageBps)
                .allowShort(allowShort)
                .execLag(execLag)
                .positionSize(positionSize)
                .riskFreePct(riskFreePct)
                .warmupBars(warmupBars)
                .stopLossPct(stopLossPct)
                .takeProfitPct(takeProfitPct)
                .timeframe(timeframe == null ? Timeframe.NATIVE : timeframe)
                .build();
    }

    /** Copy of this config with different stop-loss / take-profit % (for per-strategy runs). */
    public BacktestConfig withRisk(double stopLossPct, double takeProfitPct) {
        return builder()
                .capital(capital)
                .commissionBps(commissionBps)
                .slippageBps(slippageBps)
                .allowShort(allowShort)
                .execLag(execLag)
                .positionSize(positionSize)
                .riskFreePct(riskFreePct)
                .warmupBars(warmupBars)
                .stopLossPct(stopLossPct)
                .takeProfitPct(takeProfitPct)
                .timeframe(timeframe)
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

        public Builder capital(double capital) {
            this.capital = capital;
            return this;
        }

        public Builder commissionBps(double commissionBps) {
            this.commissionBps = commissionBps;
            return this;
        }

        public Builder slippageBps(double slippageBps) {
            this.slippageBps = slippageBps;
            return this;
        }

        public Builder allowShort(boolean allowShort) {
            this.allowShort = allowShort;
            return this;
        }

        public Builder execLag(int execLag) {
            this.execLag = execLag;
            return this;
        }

        public Builder positionSize(double positionSize) {
            this.positionSize = positionSize;
            return this;
        }

        public Builder riskFreePct(double riskFreePct) {
            this.riskFreePct = riskFreePct;
            return this;
        }

        public Builder warmupBars(int warmupBars) {
            this.warmupBars = warmupBars;
            return this;
        }

        public Builder stopLossPct(double stopLossPct) {
            this.stopLossPct = stopLossPct;
            return this;
        }

        public Builder takeProfitPct(double takeProfitPct) {
            this.takeProfitPct = takeProfitPct;
            return this;
        }

        public Builder timeframe(Timeframe timeframe) {
            this.timeframe = timeframe;
            return this;
        }

        public BacktestConfig build() {
            return new BacktestConfig(this);
        }
    }
}
