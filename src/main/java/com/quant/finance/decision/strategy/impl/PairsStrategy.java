package com.quant.finance.decision.strategy.impl;

/**
 * Market-neutral statistical arbitrage. Rolling hedge ratio on log prices, trade
 * the z-score of the spread back to the mean. Separate from the single-asset
 * catalog because it needs two aligned price series.
 */
public final class PairsStrategy {
    public final int window;
    public final double entry, exit;

    public PairsStrategy(int window, double entry, double exit) {
        this.window = window;
        this.entry = entry;
        this.exit = exit;
    }

    /** Returns {posA, posB} target weights in {-1,0,1} for the two legs. */
    public double[][] signals(double[] a, double[] b) {
        int n = a.length;
        double[] la = new double[n], lb = new double[n];
        for (int i = 0; i < n; i++) { la[i] = Math.log(a[i]); lb[i] = Math.log(b[i]); }
        double[] spread = new double[n];
        java.util.Arrays.fill(spread, Double.NaN);
        double[] z = new double[n];
        java.util.Arrays.fill(z, Double.NaN);
        for (int i = window - 1; i < n; i++) {
            double mA = 0, mB = 0;
            for (int j = i - window + 1; j <= i; j++) { mA += la[j]; mB += lb[j]; }
            mA /= window; mB /= window;
            double cov = 0, var = 0;
            for (int j = i - window + 1; j <= i; j++) { cov += (la[j] - mA) * (lb[j] - mB); var += (lb[j] - mB) * (lb[j] - mB); }
            double beta = var == 0 ? 0 : cov / var;
            spread[i] = la[i] - beta * lb[i];
        }
        for (int i = window - 1; i < n; i++) {
            if (Double.isNaN(spread[i])) continue;
            double m = 0; int c = 0;
            for (int j = Math.max(0, i - window + 1); j <= i; j++) if (!Double.isNaN(spread[j])) { m += spread[j]; c++; }
            m /= c;
            double sd = 0;
            for (int j = Math.max(0, i - window + 1); j <= i; j++) if (!Double.isNaN(spread[j])) sd += (spread[j] - m) * (spread[j] - m);
            sd = Math.sqrt(sd / c);
            z[i] = sd == 0 ? 0 : (spread[i] - m) / sd;
        }
        double[] posSpread = new double[n];
        int state = 0;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(z[i])) {
                if (state == 0) {
                    if (z[i] < -entry) state = 1;
                    else if (z[i] > entry) state = -1;
                } else if (state == 1 && z[i] > -exit) state = 0;
                else if (state == -1 && z[i] < exit) state = 0;
            }
            posSpread[i] = state;
        }
        double[] posA = posSpread.clone(), posB = new double[n];
        for (int i = 0; i < n; i++) posB[i] = -posSpread[i];
        return new double[][]{posA, posB};
    }
}
