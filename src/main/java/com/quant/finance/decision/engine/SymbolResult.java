package com.quant.finance.decision.engine;

import java.util.Map;

/**
 * One symbol's standalone result in a backtest: the full metric set from its own return stream (as if backtested alone with the run's settings)
 * and its return in each calendar year (UTC), in percent.
 */
public record SymbolResult(Map<String, Double> metrics, Map<String, Double> yearlyReturnsPct) {}
