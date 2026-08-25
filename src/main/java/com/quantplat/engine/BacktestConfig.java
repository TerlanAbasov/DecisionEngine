package com.quantplat.engine;

public final class BacktestConfig {
    public final double capital;
    public final double commissionBps;   // per side, bps of notional
    public final double slippageBps;     // per side
    public final boolean allowShort;
    public final int execLag;            // bars between signal and fill (no look-ahead)

    public BacktestConfig(double capital, double commissionBps, double slippageBps,
                          boolean allowShort, int execLag) {
        this.capital = capital;
        this.commissionBps = commissionBps;
        this.slippageBps = slippageBps;
        this.allowShort = allowShort;
        this.execLag = execLag;
    }

    public static BacktestConfig defaults() {
        return new BacktestConfig(100_000, 1.0, 2.0, true, 1);
    }
}
