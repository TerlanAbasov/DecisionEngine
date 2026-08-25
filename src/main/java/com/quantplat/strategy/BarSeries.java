package com.quantplat.strategy;

import java.time.LocalDate;

/**
 * Framework-free OHLCV container. Column arrays (not row objects) so the
 * vectorised indicators and backtester stay fast and dependency-free.
 */
public final class BarSeries {
    public final String symbol;
    public final LocalDate[] date;
    public final double[] open, high, low, close, volume;

    public BarSeries(String symbol, LocalDate[] date, double[] open, double[] high,
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
