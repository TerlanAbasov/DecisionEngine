package com.quant.finance.decision.strategy;

import java.util.Map;

/** Shared helpers so each concrete strategy stays a few lines. */
public abstract class AbstractStrategy implements TradingStrategy {

    /** The value of {@code key} in {@code params}, falling back to this strategy's default. */
    protected double param(Map<String, Double> params, String key) {
        if (params != null && params.containsKey(key)) {
            return params.get(key);
        }
        return defaultParams().get(key);
    }

    /** {@link #param} rounded to a whole number, for lengths and periods. */
    protected int intParam(Map<String, Double> params, String key) {
        return (int) Math.round(param(params, key));
    }

    /** Normalise a raw signal: NaN->0, clamp to [-1,1], drop shorts for long-only. */
    protected double[] clean(double[] signal) {
        boolean longOnly = "long_only".equals(direction());
        for (int i = 0; i < signal.length; i++) {
            double value = Double.isNaN(signal[i]) ? 0 : signal[i];
            if (value > 1) {
                value = 1;
            }
            if (value < -1) {
                value = -1;
            }
            if (longOnly && value < 0) {
                value = 0;
            }
            signal[i] = value;
        }
        return signal;
    }

    // ---- stateless regime -------------------------------------------------

    /** Long (1) on every bar where {@code longCondition} holds, short (-1) where {@code shortCondition} does, else flat. */
    protected static double[] regime(boolean[] longCondition, boolean[] shortCondition) {
        double[] position = new double[longCondition.length];
        for (int i = 0; i < position.length; i++) {
            if (longCondition[i]) {
                position[i] = 1;
            } else if (shortCondition != null && shortCondition[i]) {
                position[i] = -1;
            } else {
                position[i] = 0;
            }
        }
        return position;
    }

    // ---- held / stateful position -----------------------------------------

    /**
     * A position that is entered on an entry condition and held until its exit condition (or the opposite entry).
     * Any of the conditions except {@code longEntry} may be null.
     */
    protected static double[] hold(boolean[] longEntry, boolean[] longExit, boolean[] shortEntry, boolean[] shortExit) {
        int barCount = longEntry.length;
        double[] position = new double[barCount];
        int state = 0;
        for (int i = 0; i < barCount; i++) {
            if (state == 0) {
                if (longEntry[i]) {
                    state = 1;
                } else if (shortEntry != null && shortEntry[i]) {
                    state = -1;
                }
            } else if (state == 1) {
                if (shortEntry != null && shortEntry[i]) {
                    state = -1;
                } else if (longExit != null && longExit[i]) {
                    state = 0;
                }
            } else {
                if (longEntry[i]) {
                    state = 1;
                } else if (shortExit != null && shortExit[i]) {
                    state = 0;
                }
            }
            position[i] = state;
        }
        return position;
    }

    // ---- vectorised boolean ops (NaN-safe: comparisons with NaN are false) -

    protected static boolean[] above(double[] series, double[] other) {
        boolean[] result = new boolean[series.length];
        for (int i = 0; i < series.length; i++) {
            result[i] = !Double.isNaN(series[i]) && !Double.isNaN(other[i]) && series[i] > other[i];
        }
        return result;
    }

    protected static boolean[] below(double[] series, double[] other) {
        boolean[] result = new boolean[series.length];
        for (int i = 0; i < series.length; i++) {
            result[i] = !Double.isNaN(series[i]) && !Double.isNaN(other[i]) && series[i] < other[i];
        }
        return result;
    }

    protected static boolean[] above(double[] series, double level) {
        boolean[] result = new boolean[series.length];
        for (int i = 0; i < series.length; i++) {
            result[i] = !Double.isNaN(series[i]) && series[i] > level;
        }
        return result;
    }

    protected static boolean[] below(double[] series, double level) {
        boolean[] result = new boolean[series.length];
        for (int i = 0; i < series.length; i++) {
            result[i] = !Double.isNaN(series[i]) && series[i] < level;
        }
        return result;
    }

    protected static boolean[] and(boolean[] first, boolean[] second) {
        boolean[] result = new boolean[first.length];
        for (int i = 0; i < first.length; i++) {
            result[i] = first[i] && second[i];
        }
        return result;
    }

    /** The condition as it stood on the previous bar (false on the first bar). */
    protected static boolean[] previous(boolean[] condition) {
        boolean[] result = new boolean[condition.length];
        for (int i = 1; i < condition.length; i++) {
            result[i] = condition[i - 1];
        }
        return result;
    }

    /** The series shifted one bar later, so index i holds the previous bar's value (NaN on the first bar). */
    protected static double[] previous(double[] series) {
        double[] result = new double[series.length];
        result[0] = Double.NaN;
        System.arraycopy(series, 0, result, 1, series.length - 1);
        return result;
    }
}
