package com.quant.finance.decision.strategy;

import java.time.Instant;
import java.util.Arrays;

/**
 * Framework-free OHLCV container of column arrays (not row objects), so the vectorised indicators and backtester stay fast;
 * {@code date} is each bar's open time (UTC), since bars can be daily, weekly, hourly or minute-level.
 */
public final class BarSeries {
    public final String symbol;
    public final Instant[] date;
    public final double[] open, high, low, close, volume;

    public BarSeries(String symbol, Instant[] date, double[] open, double[] high,
                     double[] low, double[] close, double[] volume) {
        this.symbol = symbol;
        this.date = date;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public int size() {
        return close.length;
    }

    /**
     * Splits at {@code trainFraction} of the length: the leading part for selecting a parameter combo, the trailing part an unseen holdout to score it honestly.
     * Index-based, which is a close enough proxy for a time-based split since bars are regularly spaced.
     */
    public BarSeries[] trainTestSplit(double trainFraction) {
        int n = size();
        int cut = Math.max(0, Math.min(n, (int) Math.round(n * trainFraction)));
        return new BarSeries[] { slice(0, cut), slice(cut, n) };
    }

    private BarSeries slice(int from, int to) {
        return new BarSeries(symbol,
                Arrays.copyOfRange(date, from, to), Arrays.copyOfRange(open, from, to),
                Arrays.copyOfRange(high, from, to), Arrays.copyOfRange(low, from, to),
                Arrays.copyOfRange(close, from, to), Arrays.copyOfRange(volume, from, to));
    }
}
