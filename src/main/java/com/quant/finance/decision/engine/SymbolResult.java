package com.quant.finance.decision.engine;

import java.util.Map;

/**
 * One symbol's standalone result inside a backtest: the full metric set computed from that
 * symbol's own return stream (as if it had been backtested alone with the run's settings), and
 * its return in each calendar year (UTC), in percent.
 */
public record SymbolResult(Map<String, Double> metrics, Map<String, Double> yearlyReturnsPct) {}
