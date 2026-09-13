package com.quant.finance.decision.strategy;

import java.util.Map;

/** Shared helpers so each concrete strategy stays a few lines. */
public abstract class AbstractStrategy implements TradingStrategy {

    protected double p(Map<String, Double> params, String key) {
        if (params != null && params.containsKey(key)) return params.get(key);
        return defaultParams().get(key);
    }

    protected int pi(Map<String, Double> params, String key) {
        return (int) Math.round(p(params, key));
    }

    /** Normalise a raw signal: NaN->0, clamp to [-1,1], drop shorts for long-only. */
    protected double[] clean(double[] sig) {
        boolean longOnly = "long_only".equals(direction());
        for (int i = 0; i < sig.length; i++) {
            double v = Double.isNaN(sig[i]) ? 0 : sig[i];
            if (v > 1) v = 1;
            if (v < -1) v = -1;
            if (longOnly && v < 0) v = 0;
            sig[i] = v;
        }
        return sig;
    }

    // ---- stateless regime -------------------------------------------------
    protected static double[] regime(boolean[] longC, boolean[] shortC) {
        double[] out = new double[longC.length];
        for (int i = 0; i < out.length; i++)
            out[i] = longC[i] ? 1 : (shortC != null && shortC[i] ? -1 : 0);
        return out;
    }

    // ---- held / stateful position -----------------------------------------
    protected static double[] hold(boolean[] li, boolean[] lo, boolean[] si, boolean[] so) {
        int n = li.length;
        double[] pos = new double[n];
        int state = 0;
        for (int i = 0; i < n; i++) {
            if (state == 0) {
                if (li[i]) state = 1;
                else if (si != null && si[i]) state = -1;
            } else if (state == 1) {
                if (si != null && si[i]) state = -1;
                else if (lo != null && lo[i]) state = 0;
            } else {
                if (li[i]) state = 1;
                else if (so != null && so[i]) state = 0;
            }
            pos[i] = state;
        }
        return pos;
    }

    // ---- vectorised boolean ops (NaN-safe: comparisons with NaN are false) -
    protected static boolean[] gt(double[] a, double[] b) {
        boolean[] o = new boolean[a.length];
        for (int i = 0; i < a.length; i++) o[i] = !nan(a[i]) && !nan(b[i]) && a[i] > b[i];
        return o;
    }
    protected static boolean[] lt(double[] a, double[] b) {
        boolean[] o = new boolean[a.length];
        for (int i = 0; i < a.length; i++) o[i] = !nan(a[i]) && !nan(b[i]) && a[i] < b[i];
        return o;
    }
    protected static boolean[] gt(double[] a, double v) {
        boolean[] o = new boolean[a.length];
        for (int i = 0; i < a.length; i++) o[i] = !nan(a[i]) && a[i] > v;
        return o;
    }
    protected static boolean[] lt(double[] a, double v) {
        boolean[] o = new boolean[a.length];
        for (int i = 0; i < a.length; i++) o[i] = !nan(a[i]) && a[i] < v;
        return o;
    }
    protected static boolean[] and(boolean[] a, boolean[] b) {
        boolean[] o = new boolean[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i] && b[i];
        return o;
    }
    protected static boolean[] shift1(boolean[] a) {   // yesterday's condition
        boolean[] o = new boolean[a.length];
        for (int i = 1; i < a.length; i++) o[i] = a[i - 1];
        return o;
    }
    protected static double[] shift1(double[] a) {
        double[] o = new double[a.length];
        o[0] = Double.NaN;
        System.arraycopy(a, 0, o, 1, a.length - 1);
        return o;
    }
    protected static boolean nan(double v) { return Double.isNaN(v); }
}
