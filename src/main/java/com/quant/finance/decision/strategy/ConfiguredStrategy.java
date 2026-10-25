package com.quant.finance.decision.strategy;

import java.util.Map;

/**
 * Wraps a catalog strategy with the user's per-strategy controls so the backtester, scanner and signal engine share the behaviour: {@code invert} negates every
 * target position, {@code directionOverride} clamps to long_only / short_only or opens up long_short. Parameter overrides are applied upstream.
 */
public final class ConfiguredStrategy implements TradingStrategy {

    private final TradingStrategy delegate;
    private final boolean invert;
    private final String direction; // effective direction after any override

    public ConfiguredStrategy(TradingStrategy delegate, boolean invert, String directionOverride) {
        this.delegate = delegate;
        this.invert = invert;
        this.direction =
                (directionOverride == null || directionOverride.isBlank()) ? delegate.direction() : directionOverride;
    }

    public TradingStrategy delegate() {
        return delegate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String category() {
        return delegate.category();
    }

    @Override
    public String direction() {
        return direction;
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public Map<String, Double> defaultParams() {
        return delegate.defaultParams();
    }

    @Override
    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] signal = delegate.generateSignals(bars, params);
        boolean longOnly = "long_only".equals(direction);
        boolean shortOnly = "short_only".equals(direction);
        for (int i = 0; i < signal.length; i++) {
            double value = Double.isNaN(signal[i]) ? 0 : signal[i];
            if (invert) {
                value = -value;
            }
            if (value > 1) {
                value = 1;
            }
            if (value < -1) {
                value = -1;
            }
            if (longOnly && value < 0) {
                value = 0;
            }
            if (shortOnly && value > 0) {
                value = 0;
            }
            signal[i] = value;
        }
        return signal;
    }
}
