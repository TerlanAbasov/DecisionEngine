package com.quant.finance.decision.strategy;

import java.util.Map;

/**
 * A strategy consumes an OHLCV BarSeries and emits a target position per bar:
 * +1 fully long, 0 flat, -1 fully short (fractional allowed). The backtester
 * executes the target on the NEXT bar, so strategies never peek at the bar they
 * trade on.
 */
public interface TradingStrategy {
    String name();
    String category();
    String direction();          // "long_only" | "long_short"
    String description();
    Map<String, Double> defaultParams();

    double[] generateSignals(BarSeries b, Map<String, Double> params);
}
