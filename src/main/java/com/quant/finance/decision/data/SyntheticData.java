package com.quant.finance.decision.data;

import com.quant.finance.decision.strategy.BarSeries;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Reproducible, realistic OHLCV for offline development when no IB feed is present: a near-random-walk with modest drift and a whisper of momentum,
 * so backtest metrics land in believable ranges instead of the fantasy numbers of data with a baked-in cycle.
 */
public final class SyntheticData {
    private SyntheticData() {}

    public static BarSeries generate(String symbol, double years, LocalDate end) {
        int n = (int) (years * 252);
        Random rng = new Random(symbol.hashCode() * 2654435761L);
        LocalDate[] dates = businessDays(end, n);

        double mu = (0.03 + rng.nextDouble() * 0.09) / 252.0;
        double sigma = (0.18 + rng.nextDouble() * 0.14) / Math.sqrt(252.0);
        double phi = 0.03;
        double[] r = new double[n];
        r[0] = mu;
        for (int i = 1; i < n; i++) {
            double eps = rng.nextGaussian() * sigma;
            r[i] = mu + phi * (r[i - 1] - mu) + eps;
        }
        double[] close = new double[n], open = new double[n], high = new double[n],
                 low = new double[n], vol = new double[n];
        double cum = 0;
        for (int i = 0; i < n; i++) {
            cum += r[i];
            close[i] = 100.0 * Math.exp(cum);
        }
        for (int i = 0; i < n; i++) {
            double intraday = Math.abs(rng.nextGaussian() * sigma);
            double o = (i == 0 ? close[0] : close[i - 1]) * (1 + rng.nextGaussian() * sigma * 0.5);
            double h = Math.max(Math.max(close[i] * (1 + intraday), o), close[i]);
            double l = Math.min(Math.min(close[i] * (1 - intraday), o), close[i]);
            open[i] = o; high[i] = h; low[i] = l;
            vol[i] = (5e5 + rng.nextInt(4_500_000)) * (1 + intraday * 3);
        }
        Instant[] times = new Instant[n];
        for (int i = 0; i < n; i++) times[i] = dates[i].atStartOfDay(ZoneOffset.UTC).toInstant();
        return new BarSeries(symbol, times, open, high, low, close, vol);
    }

    public static BarSeries generate(String symbol) {
        return generate(symbol, 6.5, LocalDate.now());
    }

    private static LocalDate[] businessDays(LocalDate end, int n) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate d = end;
        while (days.size() < n) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                days.add(d);
            d = d.minusDays(1);
        }
        java.util.Collections.reverse(days);
        return days.toArray(new LocalDate[0]);
    }
}
