package com.quant.finance.decision.data;

import com.quant.finance.decision.strategy.BarSeries;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Reproducible, realistic OHLCV for offline development when no IB feed is present: a near-random-walk with modest drift and a whisper of momentum,
 * so backtest metrics land in believable ranges instead of the fantasy numbers of data with a baked-in cycle.
 */
public final class SyntheticData {
    private SyntheticData() {}

    public static BarSeries generate(String symbol, double years, LocalDate end) {
        int barCount = (int) (years * 252);
        Random rng = new Random(symbol.hashCode() * 2654435761L);
        LocalDate[] dates = businessDays(end, barCount);

        double mu = (0.03 + rng.nextDouble() * 0.09) / 252.0;
        double sigma = (0.18 + rng.nextDouble() * 0.14) / Math.sqrt(252.0);
        double phi = 0.03;
        double[] returns = new double[barCount];
        returns[0] = mu;
        for (int i = 1; i < barCount; i++) {
            double shock = rng.nextGaussian() * sigma;
            returns[i] = mu + phi * (returns[i - 1] - mu) + shock;
        }
        double[] close = new double[barCount];
        double[] open = new double[barCount];
        double[] high = new double[barCount];
        double[] low = new double[barCount];
        double[] volume = new double[barCount];
        double cumulativeReturn = 0;
        for (int i = 0; i < barCount; i++) {
            cumulativeReturn += returns[i];
            close[i] = 100.0 * Math.exp(cumulativeReturn);
        }
        for (int i = 0; i < barCount; i++) {
            double intraday = Math.abs(rng.nextGaussian() * sigma);
            double openPrice = (i == 0 ? close[0] : close[i - 1]) * (1 + rng.nextGaussian() * sigma * 0.5);
            double highPrice = Math.max(Math.max(close[i] * (1 + intraday), openPrice), close[i]);
            double lowPrice = Math.min(Math.min(close[i] * (1 - intraday), openPrice), close[i]);
            open[i] = openPrice;
            high[i] = highPrice;
            low[i] = lowPrice;
            volume[i] = (5e5 + rng.nextInt(4_500_000)) * (1 + intraday * 3);
        }
        Instant[] times = new Instant[barCount];
        for (int i = 0; i < barCount; i++) {
            times[i] = dates[i].atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return new BarSeries(symbol, times, open, high, low, close, volume);
    }

    public static BarSeries generate(String symbol) {
        return generate(symbol, 6.5, LocalDate.now());
    }

    private static LocalDate[] businessDays(LocalDate end, int count) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate day = end;
        while (days.size() < count) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(day);
            }
            day = day.minusDays(1);
        }
        Collections.reverse(days);
        return days.toArray(new LocalDate[0]);
    }
}
