package com.quant.finance.decision.engine;

import com.quant.finance.decision.strategy.BarSeries;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates a {@link BarSeries} up to a coarser {@link Timeframe}: first open, max high, min low, last close, summed volume, stamped with the bucket start.
 * A frame that isn't actually coarser than the data (as many buckets as bars) returns the original series.
 */
public final class BarResampler {
    private BarResampler() {}

    public static BarSeries resample(BarSeries source, Timeframe timeframe) {
        if (source == null || timeframe == null || timeframe.isNative() || source.size() < 2) {
            return source;
        }

        List<Instant> bucketDates = new ArrayList<>();
        List<Double> bucketOpen = new ArrayList<>();
        List<Double> bucketHigh = new ArrayList<>();
        List<Double> bucketLow = new ArrayList<>();
        List<Double> bucketClose = new ArrayList<>();
        List<Double> bucketVolume = new ArrayList<>();

        long currentKey = Long.MIN_VALUE;
        for (int i = 0; i < source.size(); i++) {
            long key = bucketKey(source.date[i], timeframe);
            if (key != currentKey) {
                currentKey = key;
                bucketDates.add(bucketStart(source.date[i], timeframe));
                bucketOpen.add(source.open[i]);
                bucketHigh.add(source.high[i]);
                bucketLow.add(source.low[i]);
                bucketClose.add(source.close[i]);
                bucketVolume.add(source.volume[i]);
            } else {
                int last = bucketDates.size() - 1;
                bucketHigh.set(last, Math.max(bucketHigh.get(last), source.high[i]));
                bucketLow.set(last, Math.min(bucketLow.get(last), source.low[i]));
                bucketClose.set(last, source.close[i]);
                bucketVolume.set(last, bucketVolume.get(last) + source.volume[i]);
            }
        }
        if (bucketDates.size() >= source.size()) {
            return source; // not a real downsample — keep native bars
        }
        return new BarSeries(
                source.symbol,
                bucketDates.toArray(new Instant[0]),
                toArray(bucketOpen),
                toArray(bucketHigh),
                toArray(bucketLow),
                toArray(bucketClose),
                toArray(bucketVolume));
    }

    private static double[] toArray(List<Double> values) {
        double[] result = new double[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private static long bucketKey(Instant time, Timeframe timeframe) {
        if (timeframe.seconds > 0) {
            return time.getEpochSecond() / timeframe.seconds;
        }
        ZonedDateTime date = time.atZone(ZoneOffset.UTC);
        return switch (timeframe) {
            case W1 -> date.get(IsoFields.WEEK_BASED_YEAR) * 100L + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case MN -> date.getYear() * 100L + date.getMonthValue();
            case Q1 -> date.getYear() * 10L + date.get(IsoFields.QUARTER_OF_YEAR);
            case Y1 -> date.getYear();
            default -> time.getEpochSecond();
        };
    }

    private static Instant bucketStart(Instant time, Timeframe timeframe) {
        if (timeframe.seconds > 0) {
            return Instant.ofEpochSecond((time.getEpochSecond() / timeframe.seconds) * timeframe.seconds);
        }
        LocalDate date = time.atZone(ZoneOffset.UTC).toLocalDate();
        return switch (timeframe) {
            case W1 ->
                date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();
            case MN -> date.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            case Q1 ->
                date.withDayOfMonth(1)
                        .withMonth((date.get(IsoFields.QUARTER_OF_YEAR) - 1) * 3 + 1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();
            case Y1 -> date.withDayOfYear(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            default -> time;
        };
    }
}
