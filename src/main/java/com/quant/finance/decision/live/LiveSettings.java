package com.quant.finance.decision.live;

import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.service.StrategyService;
import com.quant.finance.decision.service.UniverseService;
import com.quant.finance.decision.strategy.TradingStrategy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The paper-trading job's runtime settings; empty {@code strategyNames} / {@code symbols} mean every enabled strategy / the whole universe.
 * {@code dryRun} sends no orders, {@code allocationUsd} is $ per strategy per symbol at full signal, {@code maxGrossUsd} holds back orders past that exposure.
 */
public record LiveSettings(boolean enabled, boolean dryRun, int intervalSeconds, double allocationUsd,
                           double positionSize, boolean allowShort, String timeframeMode, int lookbackBars,
                           List<String> strategyNames, List<String> symbols, double maxGrossUsd,
                           int maxOrdersPerCycle, boolean marketHoursOnly, int fillTimeoutSeconds,
                           boolean useRiskDefaults) {

    private static final Pattern SYMBOL = Pattern.compile("[A-Z][A-Z0-9.\\-]{0,9}");

    /**
     * Off, and a dry run when first switched on (nothing is sent until that is turned off too); $100 per strategy per symbol keeps a full catalog
     * on a handful of symbols within the default exposure cap in the worst case.
     */
    public static LiveSettings defaults() {
        return new LiveSettings(false, true, 300, 100, 1.0, true, "AUTO", 300, List.of(), List.of(),
                50_000, 30, true, 30, true);
    }

    /** The strategies this job trades: the named ones, or every enabled strategy when none are named. */
    public Map<String, TradingStrategy> strategiesIn(StrategyService service) {
        return strategyNames.isEmpty() ? service.getEnabledStrategies() : service.getStrategies(strategyNames);
    }

    /** The symbols this job trades: the listed ones, or the whole universe when none are listed. */
    public List<String> symbolsIn(UniverseService universe) {
        return symbols.isEmpty() ? universe.get() : symbols;
    }

    public LiveSettings withEnabled(boolean on) {
        return new LiveSettings(on, dryRun, intervalSeconds, allocationUsd, positionSize, allowShort, timeframeMode,
                lookbackBars, strategyNames, symbols, maxGrossUsd, maxOrdersPerCycle, marketHoursOnly,
                fillTimeoutSeconds, useRiskDefaults);
    }

    /** Returns these settings with names normalised, or throws IllegalArgumentException naming the first invalid setting. */
    public LiveSettings validated(Predicate<String> knownStrategy) {
        range("intervalSeconds", intervalSeconds, 30, 86_400);
        range("allocationUsd", allocationUsd, 1, 1_000_000);
        range("positionSize", positionSize, 0.1, 5);
        range("lookbackBars", lookbackBars, 50, 2_000);
        range("maxGrossUsd", maxGrossUsd, 100, 100_000_000);
        range("maxOrdersPerCycle", maxOrdersPerCycle, 1, 500);
        range("fillTimeoutSeconds", fillTimeoutSeconds, 5, 300);

        String mode = normalisedTimeframeMode();
        List<String> names = cleaned(strategyNames, String::trim, n -> {
            if (!knownStrategy.test(n)) throw new IllegalArgumentException("Unknown strategy: " + n);
        });
        List<String> syms = cleaned(symbols, n -> n.trim().toUpperCase(), n -> {
            if (!SYMBOL.matcher(n).matches()) throw new IllegalArgumentException("Invalid symbol: " + n);
        });
        return new LiveSettings(enabled, dryRun, intervalSeconds, allocationUsd, positionSize, allowShort,
                mode, lookbackBars, names, syms, maxGrossUsd, maxOrdersPerCycle, marketHoursOnly,
                fillTimeoutSeconds, useRiskDefaults);
    }

    private String normalisedTimeframeMode() {
        String mode = timeframeMode == null ? "AUTO" : timeframeMode.trim().toUpperCase();
        if (Timeframe.isAuto(mode)) return "AUTO";
        Timeframe tf = Timeframe.from(mode);
        if (tf.isNative()) throw new IllegalArgumentException("timeframeMode must be AUTO or a frame such as M15, H1, D1");
        return tf.name();
    }

    /** Normalised, without blanks or repeats, each checked by {@code validate}. */
    private static List<String> cleaned(List<String> raw, UnaryOperator<String> normalise, Consumer<String> validate) {
        Set<String> out = new LinkedHashSet<>();
        for (String item : raw == null ? List.<String>of() : raw) {
            String s = item == null ? "" : normalise.apply(item);
            if (s.isEmpty() || out.contains(s)) continue;
            validate.accept(s);
            out.add(s);
        }
        return List.copyOf(out);
    }

    private static void range(String name, double v, double min, double max) {
        if (Double.isNaN(v) || v < min || v > max)
            throw new IllegalArgumentException(name + " must be between " + fmt(min) + " and " + fmt(max));
    }

    private static String fmt(double d) { return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d); }
}
