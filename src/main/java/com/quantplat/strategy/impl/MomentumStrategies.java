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

class MultiHorizonMomentum extends AbstractStrategy {
    public String name() { return "multi_horizon_momentum"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_short"; }
    public String description() { return "Average sign of 1/3/6-month returns — momentum that agrees across horizons."; }
    public Map<String, Double> defaultParams() { return Map.of("n1", 21.0, "n2", 63.0, "n3", 126.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] r1 = Indicators.roc(b.close, pi(pr, "n1"));
        double[] r2 = Indicators.roc(b.close, pi(pr, "n2"));
        double[] r3 = Indicators.roc(b.close, pi(pr, "n3"));
        int len = b.size();
        double[] avg = new double[len];
        for (int i = 0; i < len; i++) {
            if (nan(r1[i]) || nan(r2[i]) || nan(r3[i])) { avg[i] = Double.NaN; continue; }
            avg[i] = (r1[i] + r2[i] + r3[i]) / 3;
        }
        return clean(regime(gt(avg, 0), lt(avg, 0)));
    }
}

class MacdHistogramSlope extends AbstractStrategy {
    public String name() { return "macd_histogram_slope"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_short"; }
    public String description() { return "MACD histogram rising/falling — momentum acceleration, not just level."; }
    public Map<String, Double> defaultParams() { return Map.of("fast", 12.0, "slow", 26.0, "signal", 9.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] m = Indicators.macd(b.close, pi(pr, "fast"), pi(pr, "slow"), pi(pr, "signal"));
        double[] hist = m[2];
        double[] prev = shift1(hist);
        return clean(regime(gt(hist, prev), lt(hist, prev)));
    }
}

class ForceIndexMomentum extends AbstractStrategy {
    public String name() { return "force_index"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_short"; }
    public String description() { return "Elder's Force Index (EMA-smoothed price*volume thrust) sign."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 13.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] f = Indicators.forceIndex(b, pi(pr, "n"));
        return clean(regime(gt(f, 0), lt(f, 0)));
    }
}

class CciMomentum extends AbstractStrategy {
    public String name() { return "cci_momentum"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_short"; }
    public String description() { return "CCI as a trend gauge: long above zero, short below."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] c = Indicators.cci(b, pi(pr, "n"));
        return clean(regime(gt(c, 0), lt(c, 0)));
    }
}

class AroonOscillatorMomentum extends AbstractStrategy {
    public String name() { return "aroon_oscillator"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_short"; }
    public String description() { return "Aroon Oscillator (up - down) sign."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 25.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] a = Indicators.aroon(b, pi(pr, "n"));
        int len = b.size();
        double[] osc = new double[len];
        for (int i = 0; i < len; i++) osc[i] = (nan(a[0][i]) || nan(a[1][i])) ? Double.NaN : a[0][i] - a[1][i];
        return clean(regime(gt(osc, 0), lt(osc, 0)));
    }
}

class RocAcceleration extends AbstractStrategy {
    public String name() { return "roc_acceleration"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_short"; }
    public String description() { return "Change in the rate-of-change itself — momentum of momentum."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] r = Indicators.roc(b.close, pi(pr, "n"));
        double[] prev = shift1(r);
        return clean(regime(gt(r, prev), lt(r, prev)));
    }
}

class HullMomentum extends AbstractStrategy {
    public String name() { return "hull_momentum"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_short"; }
    public String description() { return "Slope of the Hull Moving Average — trades acceleration, not level."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 21.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] h = Indicators.hullMa(b.close, pi(pr, "n"));
        double[] prev = shift1(h);
        return clean(regime(gt(h, prev), lt(h, prev)));
    }
}

class TrixMomentum extends AbstractStrategy {
    public String name() { return "trix_momentum"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_short"; }
    public String description() { return "TRIX versus its own signal-line average (MACD-of-TRIX)."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 15.0, "signal", 9.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] t = Indicators.trix(b.close, pi(pr, "n"));
        double[] tClean = t.clone();
        for (int i = 0; i < tClean.length; i++) if (Double.isNaN(tClean[i])) tClean[i] = 0;
        double[] sig = Indicators.ema(tClean, pi(pr, "signal"));
        return clean(regime(gt(t, sig), lt(t, sig)));
    }
}

class StreakMomentum extends AbstractStrategy {
    public String name() { return "streak_momentum"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_short"; }
    public String description() { return "Consecutive up/down closing streak, capped, as a momentum signal."; }
    public Map<String, Double> defaultParams() { return Map.of("cap", 5.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int len = b.size();
        double cap = p(pr, "cap");
        double[] sig = new double[len];
        int streak = 0;
        for (int i = 1; i < len; i++) {
            if (b.close[i] > b.close[i - 1]) streak = streak >= 0 ? streak + 1 : 1;
            else if (b.close[i] < b.close[i - 1]) streak = streak <= 0 ? streak - 1 : -1;
            else streak = 0;
            sig[i] = Math.max(-cap, Math.min(cap, streak)) / cap;
        }
        return clean(sig);
    }
}

class MaStackMomentum extends AbstractStrategy {
    public String name() { return "ma_stack_momentum"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_short"; }
    public String description() { return "Position scaled by how many of 4 SMAs (20/50/100/200) price sits above."; }
    public Map<String, Double> defaultParams() { return Map.of("n1", 20.0, "n2", 50.0, "n3", 100.0, "n4", 200.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] m1 = Indicators.sma(b.close, pi(pr, "n1"));
        double[] m2 = Indicators.sma(b.close, pi(pr, "n2"));
        double[] m3 = Indicators.sma(b.close, pi(pr, "n3"));
        double[] m4 = Indicators.sma(b.close, pi(pr, "n4"));
        int len = b.size();
        double[] sig = new double[len];
        for (int i = 0; i < len; i++) {
            if (nan(m4[i])) { sig[i] = Double.NaN; continue; }
            int above = (b.close[i] > m1[i] ? 1 : 0) + (b.close[i] > m2[i] ? 1 : 0)
                      + (b.close[i] > m3[i] ? 1 : 0) + (b.close[i] > m4[i] ? 1 : 0);
            sig[i] = (above / 4.0) * 2 - 1;
        }
        return clean(sig);
    }
}
