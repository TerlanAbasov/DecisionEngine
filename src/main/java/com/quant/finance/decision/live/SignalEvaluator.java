package com.quant.finance.decision.live;

import com.quant.finance.decision.engine.BarResampler;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.live.LiveDataSource.Base;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

/**
 * Reads what a strategy wants right now: the strategy runs on the completed bars only, and its last value is the
 * target position for the bar that is forming — the same one-bar delay the backtests use. Pure functions.
 */
final class SignalEvaluator {
    private SignalEvaluator() {}

    /** Fewer completed bars than this and a strategy's indicators have not warmed up: it is not evaluated. */
    static final int MIN_BARS = 30;
    private static final int SESSION_SECONDS = 6 * 3600 + 1800;   // 6.5 h of trading a day

    /** A batch never runs on raw 1-minute bars in the backtests, and neither does the job. */
    static Timeframe liveFrame(Timeframe tf) { return tf == null || tf.isNative() ? Timeframe.M15 : tf; }

    /** Intraday frames are built from 1-minute bars, day and longer from daily bars. */
    static Base baseFor(Timeframe tf) { return tf.seconds > 0 && tf.seconds < 86_400 ? Base.MIN1 : Base.DAY1; }

    /** Calendar days of history to request so that about {@code lookbackBars} bars of {@code tf} come back. */
    static int lookbackDays(Timeframe tf, int lookbackBars) {
        if (baseFor(tf) == Base.MIN1) {
            double sessions = (double) lookbackBars * tf.seconds / SESSION_SECONDS;
            return (int) Math.min(120, Math.ceil(sessions * 1.5) + 4);          // weekends and holidays
        }
        double days = switch (tf) {
            case W1 -> lookbackBars * 7.0;
            case MN -> lookbackBars * 31.0;
            case Q1 -> lookbackBars * 92.0;
            case Y1 -> lookbackBars * 366.0;
            default -> lookbackBars * 1.5;                                        // D1: trading days -> calendar days
        };
        return (int) Math.min(1_500, Math.ceil(days) + 5);
    }

    /** {@code bars} without the bar that is still forming at {@code now} (calendar frames: always the last). */
    static BarSeries completedOnly(BarSeries bars, Timeframe tf, Instant now) {
        int n = bars.size();
        if (n == 0) return bars;
        boolean lastIncomplete = tf.seconds > 0
                ? bars.date[n - 1].plusSeconds(tf.seconds).isAfter(now)
                : true;
        if (!lastIncomplete) return bars;
        int m = n - 1;
        return new BarSeries(bars.symbol, Arrays.copyOf(bars.date, m), Arrays.copyOf(bars.open, m),
                Arrays.copyOf(bars.high, m), Arrays.copyOf(bars.low, m), Arrays.copyOf(bars.close, m),
                Arrays.copyOf(bars.volume, m));
    }

    /**
     * @return the strategy's target position (clamped to [-1, 1]) after the last completed bar, or {@code null}
     *         when there is not enough history to evaluate it
     */
    static Double lastSignal(TradingStrategy strategy, Map<String, Double> params, BarSeries base, Timeframe tf,
                             Instant now) {
        BarSeries resampled = BarResampler.resample(base, tf);
        BarSeries done = completedOnly(resampled, tf, now);
        if (done.size() < MIN_BARS) return null;
        double[] sig = strategy.generateSignals(done, params);
        if (sig == null || sig.length != done.size())
            throw new IllegalStateException(strategy.name() + " returned " + (sig == null ? "no" : sig.length)
                    + " signals for " + done.size() + " bars");
        double last = sig[sig.length - 1];
        if (Double.isNaN(last) || Double.isInfinite(last)) return 0.0;
        return Math.max(-1.0, Math.min(1.0, last));
    }
}
