package com.quant.finance.decision.live;

import com.quant.finance.decision.engine.Timeframe;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The paper-trading job's settings, editable at runtime. {@code strategyNames} / {@code symbols} empty means
 * "every enabled strategy" / "the whole universe".
 *
 * @param enabled           the job runs on its schedule
 * @param dryRun            evaluate and plan but send no orders and change no positions
 * @param intervalSeconds   time between cycles
 * @param allocationUsd     dollars each strategy puts into each symbol at full signal
 * @param positionSize      multiplier on the allocation
 * @param timeframeMode     "AUTO" (each strategy's own recommended frame) or one frame for all
 * @param lookbackBars      bars of history a strategy is evaluated on
 * @param maxGrossUsd       ceiling on the account's gross exposure; orders that would exceed it are held back
 * @param maxOrdersPerCycle safety cap on orders per cycle
 * @param marketHoursOnly   only run while the market is open
 * @param fillTimeoutSeconds how long to wait for an order to fill before cancelling it
 * @param useRiskDefaults   apply each strategy's saved stop-loss / take-profit
 */
public record LiveSettings(boolean enabled, boolean dryRun, int intervalSeconds, double allocationUsd,
                           double positionSize, boolean allowShort, String timeframeMode, int lookbackBars,
                           List<String> strategyNames, List<String> symbols, double maxGrossUsd,
                           int maxOrdersPerCycle, boolean marketHoursOnly, int fillTimeoutSeconds,
                           boolean useRiskDefaults) {

    private static final Pattern SYMBOL = Pattern.compile("[A-Z][A-Z0-9.\\-]{0,9}");

    /**
     * Off, and a dry run when first switched on: nothing is sent to the broker until that is turned off too.
     * $100 per strategy per symbol keeps the worst case (every strategy fully positioned in every symbol) of a
     * full catalog on a handful of symbols within the default exposure cap.
     */
    public static LiveSettings defaults() {
        return new LiveSettings(false, true, 300, 100, 1.0, true, "AUTO", 300, List.of(), List.of(),
                50_000, 30, true, 30, true);
    }

    /**
     * @param knownStrategy tells whether a strategy name exists
     * @return this with names normalised
     * @throws IllegalArgumentException naming the first invalid setting
     */
    public LiveSettings validated(Predicate<String> knownStrategy) {
        range("intervalSeconds", intervalSeconds, 30, 86_400);
        range("allocationUsd", allocationUsd, 1, 1_000_000);
        range("positionSize", positionSize, 0.1, 5);
        range("lookbackBars", lookbackBars, 50, 2_000);
        range("maxGrossUsd", maxGrossUsd, 100, 100_000_000);
        range("maxOrdersPerCycle", maxOrdersPerCycle, 1, 500);
        range("fillTimeoutSeconds", fillTimeoutSeconds, 5, 300);

        String mode = timeframeMode == null ? "AUTO" : timeframeMode.trim().toUpperCase();
        if (!Timeframe.isAuto(mode)) {
            Timeframe tf = Timeframe.from(mode);
            if (tf.isNative()) throw new IllegalArgumentException("timeframeMode must be AUTO or a frame such as M15, H1, D1");
            mode = tf.name();
        } else {
            mode = "AUTO";
        }

        List<String> names = new ArrayList<>();
        for (String n : strategyNames == null ? List.<String>of() : strategyNames) {
            String s = n == null ? "" : n.trim();
            if (s.isEmpty() || names.contains(s)) continue;
            if (!knownStrategy.test(s)) throw new IllegalArgumentException("Unknown strategy: " + s);
            names.add(s);
        }
        List<String> syms = new ArrayList<>();
        for (String n : symbols == null ? List.<String>of() : symbols) {
            String s = n == null ? "" : n.trim().toUpperCase();
            if (s.isEmpty() || syms.contains(s)) continue;
            if (!SYMBOL.matcher(s).matches()) throw new IllegalArgumentException("Invalid symbol: " + s);
            syms.add(s);
        }
        return new LiveSettings(enabled, dryRun, intervalSeconds, allocationUsd, positionSize, allowShort, mode,
                lookbackBars, List.copyOf(names), List.copyOf(syms), maxGrossUsd, maxOrdersPerCycle, marketHoursOnly,
                fillTimeoutSeconds, useRiskDefaults);
    }

    private static void range(String name, double v, double min, double max) {
        if (Double.isNaN(v) || v < min || v > max)
            throw new IllegalArgumentException(name + " must be between " + fmt(min) + " and " + fmt(max));
    }

    private static String fmt(double d) { return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d); }
}
