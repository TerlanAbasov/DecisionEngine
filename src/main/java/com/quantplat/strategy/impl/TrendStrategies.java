package com.quantplat.strategy.impl;

import com.quantplat.strategy.AbstractStrategy;
import com.quantplat.strategy.BarSeries;
import com.quantplat.strategy.Indicators;
import java.util.Map;

class SmaCross extends AbstractStrategy {
    public String name() { return "sma_cross"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Fast/slow simple-MA crossover (golden/death cross)."; }
    public Map<String, Double> defaultParams() { return Map.of("fast", 50.0, "slow", 200.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] f = Indicators.sma(b.close, pi(pr, "fast")), s = Indicators.sma(b.close, pi(pr, "slow"));
        return clean(regime(gt(f, s), lt(f, s)));
    }
}

class EmaCross extends AbstractStrategy {
    public String name() { return "ema_cross"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Exponential MA crossover — reacts faster than SMA."; }
    public Map<String, Double> defaultParams() { return Map.of("fast", 20.0, "slow", 50.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] f = Indicators.ema(b.close, pi(pr, "fast")), s = Indicators.ema(b.close, pi(pr, "slow"));
        return clean(regime(gt(f, s), lt(f, s)));
    }
}

class MacdTrend extends AbstractStrategy {
    public String name() { return "macd"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Trade the MACD line versus its signal line."; }
    public Map<String, Double> defaultParams() { return Map.of("fast", 12.0, "slow", 26.0, "signal", 9.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] m = Indicators.macd(b.close, pi(pr, "fast"), pi(pr, "slow"), pi(pr, "signal"));
        return clean(regime(gt(m[0], m[1]), lt(m[0], m[1])));
    }
}

class TripleMa extends AbstractStrategy {
    public String name() { return "triple_ma"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Long when fast>mid>slow (stacked up), short when stacked down."; }
    public Map<String, Double> defaultParams() { return Map.of("fast", 10.0, "mid", 30.0, "slow", 60.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] f = Indicators.ema(b.close, pi(pr, "fast"));
        double[] m = Indicators.ema(b.close, pi(pr, "mid"));
        double[] s = Indicators.ema(b.close, pi(pr, "slow"));
        return clean(regime(and(gt(f, m), gt(m, s)), and(lt(f, m), lt(m, s))));
    }
}

class AdxTrend extends AbstractStrategy {
    public String name() { return "adx_trend"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Only trade when ADX confirms a trend; direction from +DI/-DI."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 14.0, "adxMin", 25.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] a = Indicators.adx(b, pi(pr, "n"));
        boolean[] strong = gt(a[0], p(pr, "adxMin"));
        return clean(regime(and(strong, gt(a[1], a[2])), and(strong, gt(a[2], a[1]))));
    }
}

class SupertrendStrat extends AbstractStrategy {
    public String name() { return "supertrend"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "ATR-based Supertrend: follow the flip of the trend line."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 10.0, "mult", 3.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] st = Indicators.supertrend(b, pi(pr, "n"), p(pr, "mult"));
        return clean(regime(gt(st, 0), lt(st, 0)));
    }
}

class KalmanTrend extends AbstractStrategy {
    public String name() { return "kalman_trend"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Kalman-filtered price trend; trade slope + price cross."; }
    public Map<String, Double> defaultParams() { return Map.of("q", 0.0001, "r", 0.01); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] k = Indicators.kalman(b.close, p(pr, "q"), p(pr, "r"));
        double[] slope = new double[k.length];
        for (int i = 1; i < k.length; i++) slope[i] = k[i] - k[i - 1];
        return clean(regime(and(gt(b.close, k), gt(slope, 0)), and(lt(b.close, k), lt(slope, 0))));
    }
}
