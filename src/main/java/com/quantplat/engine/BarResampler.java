package com.quantplat.engine;

import com.quantplat.strategy.BarSeries;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates an OHLCV {@link BarSeries} up to a coarser {@link Timeframe}.
 *
 * <p>Downsample only: open = first bar's open, high = max, low = min, close = last
 * bar's close, volume = summed. The bucket is stamped with its start instant. If the
 * requested frame is not actually coarser than the data (it would produce as many
 * buckets as input bars), the original series is returned unchanged.
 */
public final class BarResampler {
    private BarResampler() {}

    public static BarSeries resample(BarSeries src, Timeframe tf) {
        if (src == null || tf == null || tf.isNative() || src.size() < 2) return src;

        List<Instant> bd = new ArrayList<>();
        List<Double> bo = new ArrayList<>(), bh = new ArrayList<>(), bl = new ArrayList<>(),
                bc = new ArrayList<>(), bv = new ArrayList<>();

        long curKey = Long.MIN_VALUE;
        for (int i = 0; i < src.size(); i++) {
            long key = bucketKey(src.date[i], tf);
            if (key != curKey) {
                curKey = key;
                bd.add(bucketStart(src.date[i], tf));
                bo.add(src.open[i]); bh.add(src.high[i]); bl.add(src.low[i]);
                bc.add(src.close[i]); bv.add(src.volume[i]);
            } else {
                int j = bd.size() - 1;
                bh.set(j, Math.max(bh.get(j), src.high[i]));
                bl.set(j, Math.min(bl.get(j), src.low[i]));
                bc.set(j, src.close[i]);
                bv.set(j, bv.get(j) + src.volume[i]);
            }
        }
        if (bd.size() >= src.size()) return src;   // not a real downsample — keep native bars

        int n = bd.size();
        Instant[] date = bd.toArray(new Instant[0]);
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n], v = new double[n];
        for (int i = 0; i < n; i++) {
            o[i] = bo.get(i); h[i] = bh.get(i); l[i] = bl.get(i); c[i] = bc.get(i); v[i] = bv.get(i);
        }
        return new BarSeries(src.symbol, date, o, h, l, c, v);
    }

    private static long bucketKey(Instant t, Timeframe tf) {
        if (tf.seconds > 0) return t.getEpochSecond() / tf.seconds;
        var d = t.atZone(ZoneOffset.UTC);
        return switch (tf) {
            case W1 -> d.get(IsoFields.WEEK_BASED_YEAR) * 100L + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case MN -> d.getYear() * 100L + d.getMonthValue();
            case Q1 -> d.getYear() * 10L + d.get(IsoFields.QUARTER_OF_YEAR);
            case Y1 -> d.getYear();
            default -> t.getEpochSecond();
        };
    }

    private static Instant bucketStart(Instant t, Timeframe tf) {
        if (tf.seconds > 0)
            return Instant.ofEpochSecond((t.getEpochSecond() / tf.seconds) * tf.seconds);
        var d = t.atZone(ZoneOffset.UTC).toLocalDate();
        return switch (tf) {
            case W1 -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            case MN -> d.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            case Q1 -> d.withDayOfMonth(1).withMonth((d.get(IsoFields.QUARTER_OF_YEAR) - 1) * 3 + 1)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            case Y1 -> d.withDayOfYear(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            default -> t;
        };
    }
}
