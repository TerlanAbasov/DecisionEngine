package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.strategy.BarSeries;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * A sliding window of one symbol's bars that is topped up instead of re-downloaded: the first call fetches the
 * whole lookback, later calls only what is newer than the last cached bar. Not thread-safe; callers synchronise.
 */
final class BarWindow {

    /** A bar as received. */
    record Bar(double open, double high, double low, double close, double volume) {}

    /** Fetches bars in {@code [from, to]}, oldest first. */
    interface Fetcher {
        Map<Instant, Bar> fetch(Instant from, Instant to);
    }

    private final TreeMap<Instant, Bar> bars = new TreeMap<>();
    private Instant coveredFrom; // the earliest instant we have already asked Alpaca for

    /** Tops the window up to {@code now}, trims it to {@code lookback} and returns it as a series. */
    BarSeries update(String symbol, Duration lookback, Instant now, Fetcher fetcher) {
        Instant windowStart = now.minus(lookback);
        if (coveredFrom == null) {
            bars.putAll(fetcher.fetch(windowStart, now)); // first use: the whole lookback, one go
            coveredFrom = windowStart;
        } else {
            if (windowStart.isBefore(coveredFrom)) { // a longer lookback than before: backfill
                bars.putAll(fetcher.fetch(windowStart, coveredFrom));
                coveredFrom = windowStart;
            }
            // top up from the newest cached bar (it may have been incomplete when last seen); with nothing
            // cached yet, ask for the whole window again
            bars.putAll(fetcher.fetch(bars.isEmpty() ? windowStart : bars.lastKey(), now));
        }
        bars.headMap(windowStart).clear();
        return toSeries(symbol);
    }

    private BarSeries toSeries(String symbol) {
        int barCount = bars.size();
        Instant[] date = new Instant[barCount];
        double[] open = new double[barCount];
        double[] high = new double[barCount];
        double[] low = new double[barCount];
        double[] close = new double[barCount];
        double[] volume = new double[barCount];
        int index = 0;
        for (Map.Entry<Instant, Bar> entry : bars.entrySet()) {
            date[index] = entry.getKey();
            Bar bar = entry.getValue();
            open[index] = bar.open();
            high[index] = bar.high();
            low[index] = bar.low();
            close[index] = bar.close();
            volume[index] = bar.volume();
            index++;
        }
        return new BarSeries(symbol, date, open, high, low, close, volume);
    }
}
