package com.quantplat.strategy.impl;

import com.quantplat.strategy.AbstractStrategy;
import com.quantplat.strategy.BarSeries;
import com.quantplat.strategy.Indicators;
import java.util.Map;

class RocMomentum extends AbstractStrategy {
    public String name() { return "roc_momentum"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_short"; }
    public String description() { return "Rate-of-change momentum: ride positive/negative n-day returns."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 90.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] r = Indicators.roc(b.close, pi(pr, "n"));
        return clean(regime(gt(r, 0), lt(r, 0)));
    }
}

class High52wBreakout extends AbstractStrategy {
    public String name() { return "high_52w_breakout"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_only"; }
    public String description() { return "Buy new 52-week highs; exit on a 6-month low (Darvas-style)."; }
    public Map<String, Double> defaultParams() { return Map.of("hi", 252.0, "exitLo", 126.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        boolean[] in = gt(b.close, shift1(Indicators.rollingMax(b.close, pi(pr, "hi"))));
        boolean[] out = lt(b.close, shift1(Indicators.rollingMin(b.close, pi(pr, "exitLo"))));
        return clean(hold(in, out, null, null));
    }
}

class DualMomentum extends AbstractStrategy {
    public String name() { return "dual_momentum"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_only"; }
    public String description() { return "Absolute momentum: long only when 12m and 3m returns agree."; }
    public Map<String, Double> defaultParams() { return Map.of("longN", 252.0, "shortN", 63.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        boolean[] c = and(gt(Indicators.roc(b.close, pi(pr, "longN")), 0),
                          gt(Indicators.roc(b.close, pi(pr, "shortN")), 0));
        return clean(regime(c, null));
    }
}

class VolScaledMomentum extends AbstractStrategy {
    public String name() { return "vol_scaled_momentum"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_short"; }
    public String description() { return "Momentum sign sized inversely to realised volatility (risk-parity flavour)."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 90.0, "volN", 20.0, "targetVol", 0.15, "cap", 1.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] roc = Indicators.roc(b.close, pi(pr, "n"));
        double[] ret = new double[b.size()];
        for (int i = 1; i < b.size(); i++) ret[i] = b.close[i] / b.close[i - 1] - 1;
        double[] rv = Indicators.stddev(ret, pi(pr, "volN"));
        double[] sig = new double[b.size()];
        double tv = p(pr, "targetVol"), cap = p(pr, "cap");
        for (int i = 0; i < b.size(); i++) {
            if (nan(roc[i]) || nan(rv[i]) || rv[i] == 0) continue;
            double annVol = rv[i] * Math.sqrt(252);
            double scale = Math.min(cap, tv / annVol);
            sig[i] = Math.signum(roc[i]) * scale;
        }
        return clean(sig);
    }
}

class RsiMomentum extends AbstractStrategy {
    public String name() { return "rsi_momentum"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_short"; }
    public String description() { return "RSI as a trend gauge: long above 50, short below."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 14.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] r = Indicators.rsi(b.close, pi(pr, "n"));
        return clean(regime(gt(r, 50), lt(r, 50)));
    }
}
