package com.quant.finance.decision.live;

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
    interface Fetcher { Map<Instant, Bar> fetch(Instant from, Instant to); }

    private final TreeMap<Instant, Bar> bars = new TreeMap<>();
    private Instant coveredFrom;   // the earliest instant we have already asked Alpaca for

    /**
     * @return the window after topping it up to {@code now}, trimmed to {@code lookback}
     */
    BarSeries update(String symbol, Duration lookback, Instant now, Fetcher fetcher) {
        Instant windowStart = now.minus(lookback);
        if (coveredFrom == null) {
            bars.putAll(fetcher.fetch(windowStart, now));              // first use: the whole lookback, one go
            coveredFrom = windowStart;
        } else {
            if (windowStart.isBefore(coveredFrom)) {                   // a longer lookback than before: backfill
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
        int n = bars.size();
        Instant[] date = new Instant[n];
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n], v = new double[n];
        int i = 0;
        for (Map.Entry<Instant, Bar> e : bars.entrySet()) {
            date[i] = e.getKey();
            Bar b = e.getValue();
            o[i] = b.open(); h[i] = b.high(); l[i] = b.low(); c[i] = b.close(); v[i] = b.volume();
            i++;
        }
        return new BarSeries(symbol, date, o, h, l, c, v);
    }
}
