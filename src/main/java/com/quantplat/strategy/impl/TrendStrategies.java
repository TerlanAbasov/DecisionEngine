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

class HullMaTrend extends AbstractStrategy {
    public String name() { return "hull_ma_trend"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Price vs Hull Moving Average — a faster, lower-lag trend filter."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 21.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] h = Indicators.hullMa(b.close, pi(pr, "n"));
        return clean(regime(gt(b.close, h), lt(b.close, h)));
    }
}

class DemaCross extends AbstractStrategy {
    public String name() { return "dema_cross"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Double-EMA crossover — less lag than a plain EMA cross."; }
    public Map<String, Double> defaultParams() { return Map.of("fast", 12.0, "slow", 26.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] f = Indicators.dema(b.close, pi(pr, "fast")), s = Indicators.dema(b.close, pi(pr, "slow"));
        return clean(regime(gt(f, s), lt(f, s)));
    }
}

class TemaCross extends AbstractStrategy {
    public String name() { return "tema_cross"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Triple-EMA crossover — even less lag, more whipsaw-prone."; }
    public Map<String, Double> defaultParams() { return Map.of("fast", 9.0, "slow", 21.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] f = Indicators.tema(b.close, pi(pr, "fast")), s = Indicators.tema(b.close, pi(pr, "slow"));
        return clean(regime(gt(f, s), lt(f, s)));
    }
}

class VwmaTrend extends AbstractStrategy {
    public String name() { return "vwma_trend"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Price vs volume-weighted moving average trend filter."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] v = Indicators.vwma(b, pi(pr, "n"));
        return clean(regime(gt(b.close, v), lt(b.close, v)));
    }
}

class AroonTrend extends AbstractStrategy {
    public String name() { return "aroon_trend"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Aroon up/down: trade the stronger, dominant side above a strength floor."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 25.0, "floor", 70.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] a = Indicators.aroon(b, pi(pr, "n"));
        double floor = p(pr, "floor");
        boolean[] longC = and(gt(a[0], a[1]), gt(a[0], floor));
        boolean[] shortC = and(gt(a[1], a[0]), gt(a[1], floor));
        return clean(regime(longC, shortC));
    }
}

class VortexTrend extends AbstractStrategy {
    public String name() { return "vortex_trend"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Vortex indicator: trade the VI+/VI- crossover."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 14.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] v = Indicators.vortex(b, pi(pr, "n"));
        return clean(regime(gt(v[0], v[1]), lt(v[0], v[1])));
    }
}

class TrixSignal extends AbstractStrategy {
    public String name() { return "trix_signal"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Triple-smoothed EMA rate of change (TRIX) versus its zero line."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 15.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] t = Indicators.trix(b.close, pi(pr, "n"));
        return clean(regime(gt(t, 0), lt(t, 0)));
    }
}

class LinregSlope extends AbstractStrategy {
    public String name() { return "linreg_slope"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Sign of the rolling linear-regression slope of price."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] lr = Indicators.linreg(b.close, pi(pr, "n"));
        return clean(regime(gt(lr[1], 0), lt(lr[1], 0)));
    }
}

class ParabolicSar extends AbstractStrategy {
    public String name() { return "parabolic_sar"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Wilder's Parabolic SAR: follow the flip of the stop-and-reverse dot."; }
    public Map<String, Double> defaultParams() { return Map.of("step", 0.02, "max", 0.2); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] dir = Indicators.psar(b, p(pr, "step"), p(pr, "max"));
        return clean(regime(gt(dir, 0), lt(dir, 0)));
    }
}

class EmaRibbon extends AbstractStrategy {
    public String name() { return "ema_ribbon"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "5-EMA ribbon (8/13/21/34/55): long when fully stacked up, short when stacked down."; }
    public Map<String, Double> defaultParams() { return Map.of("e1", 8.0, "e2", 13.0, "e3", 21.0, "e4", 34.0, "e5", 55.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] e1 = Indicators.ema(b.close, pi(pr, "e1"));
        double[] e2 = Indicators.ema(b.close, pi(pr, "e2"));
        double[] e3 = Indicators.ema(b.close, pi(pr, "e3"));
        double[] e4 = Indicators.ema(b.close, pi(pr, "e4"));
        double[] e5 = Indicators.ema(b.close, pi(pr, "e5"));
        boolean[] up = and(and(gt(e1, e2), gt(e2, e3)), and(gt(e3, e4), gt(e4, e5)));
        boolean[] dn = and(and(lt(e1, e2), lt(e2, e3)), and(lt(e3, e4), lt(e4, e5)));
        return clean(regime(up, dn));
    }
}

class ElderRayTrend extends AbstractStrategy {
    public String name() { return "elder_ray"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Elder Ray bull/bear power around a 13-EMA trend filter."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 13.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] e = Indicators.ema(b.close, pi(pr, "n"));
        int len = b.size();
        double[] bull = new double[len], bear = new double[len];
        for (int i = 0; i < len; i++) {
            bull[i] = nan(e[i]) ? Double.NaN : b.high[i] - e[i];
            bear[i] = nan(e[i]) ? Double.NaN : b.low[i] - e[i];
        }
        return clean(regime(and(gt(bull, 0), gt(bear, 0)), and(lt(bull, 0), lt(bear, 0))));
    }
}

class CoppockCurve extends AbstractStrategy {
    public String name() { return "coppock_curve"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Coppock curve: smoothed sum of two long-horizon ROCs versus zero."; }
    public Map<String, Double> defaultParams() { return Map.of("roc1", 14.0, "roc2", 11.0, "smooth", 10.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] r1 = Indicators.roc(b.close, pi(pr, "roc1"));
        double[] r2 = Indicators.roc(b.close, pi(pr, "roc2"));
        int len = b.size();
        double[] sum = new double[len];
        for (int i = 0; i < len; i++) sum[i] = (nan(r1[i]) ? 0 : r1[i]) + (nan(r2[i]) ? 0 : r2[i]);
        double[] c = Indicators.ema(sum, pi(pr, "smooth"));
        return clean(regime(gt(c, 0), lt(c, 0)));
    }
}

class DonchianMidline extends AbstractStrategy {
    public String name() { return "donchian_midline"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Price versus the midline of an N-day Donchian channel."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = pi(pr, "n");
        double[] hi = Indicators.rollingMax(b.high, n), lo = Indicators.rollingMin(b.low, n);
        int len = b.size();
        double[] mid = new double[len];
        for (int i = 0; i < len; i++) mid[i] = (nan(hi[i]) || nan(lo[i])) ? Double.NaN : (hi[i] + lo[i]) / 2;
        return clean(regime(gt(b.close, mid), lt(b.close, mid)));
    }
}
