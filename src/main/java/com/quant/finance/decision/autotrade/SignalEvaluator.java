package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.autotrade.LiveDataSource.Base;
import com.quant.finance.decision.engine.BarResampler;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

/**
 * Reads what a strategy wants right now: the strategy runs on the completed bars only, and its last value is the
 * target position for the bar that is forming — the same one-bar delay the backtests use. Pure functions.
 */
public final class SignalEvaluator {
    private SignalEvaluator() {}

    /** Fewer completed bars than this and a strategy's indicators have not warmed up: it is not evaluated. */
    static final int MIN_BARS = 30;

    private static final int SESSION_SECONDS = 6 * 3600 + 1800; // 6.5 h of trading a day

    /** A batch never runs on raw 1-minute bars in the backtests, and neither does auto trading. */
    public static Timeframe liveFrame(Timeframe frame) {
        return frame == null || frame.isNative() ? Timeframe.M15 : frame;
    }

    /** Intraday frames are built from 1-minute bars, day and longer from daily bars. */
    public static Base baseFor(Timeframe frame) {
        return frame.seconds > 0 && frame.seconds < 86_400 ? Base.MIN1 : Base.DAY1;
    }

    /** Calendar days of history to request so that about {@code lookbackBars} bars of {@code tf} come back. */
    public static int lookbackDays(Timeframe frame, int lookbackBars) {
        if (baseFor(frame) == Base.MIN1) {
            double sessions = (double) lookbackBars * frame.seconds / SESSION_SECONDS;
            return (int) Math.min(120, Math.ceil(sessions * 1.5) + 4); // weekends and holidays
        }
        double days =
                switch (frame) {
                    case W1 -> lookbackBars * 7.0;
                    case MN -> lookbackBars * 31.0;
                    case Q1 -> lookbackBars * 92.0;
                    case Y1 -> lookbackBars * 366.0;
                    default -> lookbackBars * 1.5; // D1: trading days -> calendar days
                };
        return (int) Math.min(1_500, Math.ceil(days) + 5);
    }

    /** {@code bars} without the bar that is still forming at {@code now} (calendar frames: always the last). */
    static BarSeries completedOnly(BarSeries bars, Timeframe frame, Instant now) {
        int barCount = bars.size();
        if (barCount == 0) {
            return bars;
        }
        boolean lastIncomplete = frame.seconds <= 0
                || bars.date[barCount - 1].plusSeconds(frame.seconds).isAfter(now);
        if (!lastIncomplete) {
            return bars;
        }
        int completedCount = barCount - 1;
        return new BarSeries(
                bars.symbol,
                Arrays.copyOf(bars.date, completedCount),
                Arrays.copyOf(bars.open, completedCount),
                Arrays.copyOf(bars.high, completedCount),
                Arrays.copyOf(bars.low, completedCount),
                Arrays.copyOf(bars.close, completedCount),
                Arrays.copyOf(bars.volume, completedCount));
    }

    /** What a strategy wants after the last completed bar (its own {@code open}/{@code high}/{@code low}/{@code close}) and after the one before it. */
    public record Reading(
            Instant barOpen,
            double open,
            double high,
            double low,
            double close,
            double signal,
            double previousSignal) {}

    /** The strategy's target position (clamped to [-1, 1]) after the last completed bar, or null when there is not enough history to evaluate it. */
    static Double lastSignal(
            TradingStrategy strategy, Map<String, Double> params, BarSeries base, Timeframe frame, Instant now) {
        Reading reading = read(strategy, params, base, frame, now);
        return reading == null ? null : reading.signal();
    }

    /** Like {@link #lastSignal} but also says which bar it is and what the bar before wanted, so a change of mind can be told from a standing position. */
    public static Reading read(
            TradingStrategy strategy, Map<String, Double> params, BarSeries base, Timeframe frame, Instant now) {
        BarSeries resampled = BarResampler.resample(base, frame);
        BarSeries done = completedOnly(resampled, frame, now);
        if (done.size() < MIN_BARS) {
            return null;
        }
        double[] sig = strategy.generateSignals(done, params);
        if (sig == null || sig.length != done.size()) {
            throw new IllegalStateException(strategy.name() + " returned " + (sig == null ? "no" : sig.length)
                    + " signals for " + done.size() + " bars");
        }
        int last = done.size() - 1;
        return new Reading(
                done.date[last],
                done.open[last],
                done.high[last],
                done.low[last],
                done.close[last],
                clamp(sig[last]),
                clamp(sig[last - 1]));
    }

    /** A signal within [-1, 1]; one that is not a number counts as flat. */
    private static double clamp(double signal) {
        return Double.isNaN(signal) || Double.isInfinite(signal) ? 0.0 : Math.max(-1.0, Math.min(1.0, signal));
    }
}
