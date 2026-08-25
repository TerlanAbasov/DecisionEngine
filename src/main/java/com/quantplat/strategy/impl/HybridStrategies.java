package com.quantplat.strategy.impl;

import com.quantplat.strategy.AbstractStrategy;
import com.quantplat.strategy.BarSeries;
import com.quantplat.strategy.Indicators;
import java.util.Map;

class MacdRsiCombo extends AbstractStrategy {
    public String name() { return "macd_rsi_combo"; }
    public String category() { return "hybrid"; }
    public String direction() { return "long_short"; }
    public String description() { return "Confluence filter: MACD trend AND RSI regime must agree."; }
    public Map<String, Double> defaultParams() { return Map.of("rsiN", 14.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] m = Indicators.macd(b.close, 12, 26, 9);
        double[] r = Indicators.rsi(b.close, pi(pr, "rsiN"));
        return clean(regime(and(gt(m[0], m[1]), gt(r, 50)), and(lt(m[0], m[1]), lt(r, 50))));
    }
}

class DualMaAtrStop extends AbstractStrategy {
    public String name() { return "dual_ma_atr_stop"; }
    public String category() { return "hybrid"; }
    public String direction() { return "long_only"; }
    public String description() { return "EMA cross entry with a chandelier ATR trailing stop for exits."; }
    public Map<String, Double> defaultParams() { return Map.of("fast", 20.0, "slow", 50.0, "atrN", 22.0, "atrMult", 3.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] f = Indicators.ema(b.close, pi(pr, "fast"));
        double[] s = Indicators.ema(b.close, pi(pr, "slow"));
        double[] atr = Indicators.atr(b, pi(pr, "atrN"));
        double mult = p(pr, "atrMult");
        int n = b.size();
        double[] pos = new double[n];
        int state = 0;
        double peak = Double.NaN;
        for (int i = 1; i < n; i++) {
            boolean cross = !nan(f[i]) && !nan(s[i]) && f[i] > s[i] && f[i - 1] <= s[i - 1];
            if (state == 0) {
                if (cross) { state = 1; peak = b.high[i]; }
            } else {
                peak = Math.max(peak, b.high[i]);
                double stop = peak - mult * (nan(atr[i]) ? 0 : atr[i]);
                if (b.close[i] < stop || (!nan(f[i]) && !nan(s[i]) && f[i] < s[i])) { state = 0; peak = Double.NaN; }
            }
            pos[i] = state;
        }
        return clean(pos);
    }
}

class GapReversion extends AbstractStrategy {
    public String name() { return "gap_reversion"; }
    public String category() { return "pattern"; }
    public String direction() { return "long_short"; }
    public String description() { return "Fade large overnight gaps: short gap-ups, buy gap-downs."; }
    public Map<String, Double> defaultParams() { return Map.of("gap", 0.03); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double g = p(pr, "gap");
        double[] sig = new double[b.size()];
        for (int i = 1; i < b.size(); i++) {
            double gap = b.open[i] / b.close[i - 1] - 1;
            if (gap > g) sig[i] = -1;
            else if (gap < -g) sig[i] = 1;
        }
        return clean(sig);
    }
}

class SeasonalityTom extends AbstractStrategy {
    public String name() { return "seasonality_tom"; }
    public String category() { return "seasonal"; }
    public String direction() { return "long_only"; }
    public String description() { return "Turn-of-month: hold the last N and first M trading days of each month."; }
    public Map<String, Double> defaultParams() { return Map.of("pre", 1.0, "post", 3.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = b.size(), pre = pi(pr, "pre"), post = pi(pr, "post");
        int[] ym = new int[n];
        for (int i = 0; i < n; i++) ym[i] = b.date[i].getYear() * 12 + b.date[i].getMonthValue();
        int[] posIn = new int[n], fromEnd = new int[n];
        for (int i = 0; i < n; i++) posIn[i] = (i == 0 || ym[i] != ym[i - 1]) ? 0 : posIn[i - 1] + 1;
        for (int i = n - 1; i >= 0; i--) fromEnd[i] = (i == n - 1 || ym[i] != ym[i + 1]) ? 0 : fromEnd[i + 1] + 1;
        double[] sig = new double[n];
        for (int i = 0; i < n; i++) sig[i] = (posIn[i] < post || fromEnd[i] < pre) ? 1 : 0;
        return clean(sig);
    }
}
