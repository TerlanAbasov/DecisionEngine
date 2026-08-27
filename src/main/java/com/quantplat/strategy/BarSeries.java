package com.quantplat.strategy;

import java.time.Instant;

/**
 * Framework-free OHLCV container. Column arrays (not row objects) so the
 * vectorised indicators and backtester stay fast and dependency-free.
 *
 * <p>{@code date} is each bar's open time (UTC) rather than a calendar date, since bars can be
 * daily, weekly, hourly, or minute-level depending on {@code quantplat.alpaca.timeframe}.
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
}
