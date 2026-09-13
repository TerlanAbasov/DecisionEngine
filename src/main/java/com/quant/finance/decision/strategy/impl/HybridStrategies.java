package com.quant.finance.decision.strategy.impl;

import com.quant.finance.decision.strategy.AbstractStrategy;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.Indicators;
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

class TripleConfirmation extends AbstractStrategy {
    public String name() { return "triple_confirmation"; }
    public String category() { return "hybrid"; }
    public String direction() { return "long_short"; }
    public String description() { return "ADX strength + MACD direction + RSI regime must all agree."; }
    public Map<String, Double> defaultParams() { return Map.of("adxN", 14.0, "adxMin", 20.0, "rsiN", 14.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] a = Indicators.adx(b, pi(pr, "adxN"));
        double[][] m = Indicators.macd(b.close, 12, 26, 9);
        double[] r = Indicators.rsi(b.close, pi(pr, "rsiN"));
        boolean[] strong = gt(a[0], p(pr, "adxMin"));
        boolean[] up = and(strong, and(gt(m[0], m[1]), gt(r, 50)));
        boolean[] dn = and(strong, and(lt(m[0], m[1]), lt(r, 50)));
        return clean(regime(up, dn));
    }
}

class TrendVolumeCombo extends AbstractStrategy {
    public String name() { return "trend_volume_combo"; }
    public String category() { return "hybrid"; }
    public String direction() { return "long_short"; }
    public String description() { return "EMA trend confirmed by OBV sitting above/below its own EMA."; }
    public Map<String, Double> defaultParams() { return Map.of("fast", 20.0, "slow", 50.0, "obvEma", 20.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] f = Indicators.ema(b.close, pi(pr, "fast"));
        double[] s = Indicators.ema(b.close, pi(pr, "slow"));
        double[] o = Indicators.obv(b);
        double[] oe = Indicators.ema(o, pi(pr, "obvEma"));
        boolean[] up = and(gt(f, s), gt(o, oe));
        boolean[] dn = and(lt(f, s), lt(o, oe));
        return clean(regime(up, dn));
    }
}

class BreakoutMomentumCombo extends AbstractStrategy {
    public String name() { return "breakout_momentum_combo"; }
    public String category() { return "hybrid"; }
    public String direction() { return "long_only"; }
    public String description() { return "Donchian breakout that also carries positive rate-of-change momentum."; }
    public Map<String, Double> defaultParams() { return Map.of("entryN", 20.0, "exitN", 10.0, "rocN", 63.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        boolean[] brk = gt(b.close, shift1(Indicators.rollingMax(b.high, pi(pr, "entryN"))));
        boolean[] mom = gt(Indicators.roc(b.close, pi(pr, "rocN")), 0);
        boolean[] in = and(brk, mom);
        boolean[] out = lt(b.close, shift1(Indicators.rollingMin(b.low, pi(pr, "exitN"))));
        return clean(hold(in, out, null, null));
    }
}

class RsiPullbackTrendFilter extends AbstractStrategy {
    public String name() { return "rsi_pullback_trend_filter"; }
    public String category() { return "hybrid"; }
    public String direction() { return "long_only"; }
    public String description() { return "RSI(2) pullback entries, gated by a rising (not just above) 200-SMA."; }
    public Map<String, Double> defaultParams() { return Map.of("trendMa", 200.0, "rsiN", 2.0, "entry", 10.0, "exitMa", 5.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] ma = Indicators.sma(b.close, pi(pr, "trendMa"));
        boolean[] aboveMa = gt(b.close, ma);
        boolean[] rising = gt(ma, shift1(ma));
        double[] r = Indicators.rsi(b.close, pi(pr, "rsiN"));
        boolean[] in = and(and(aboveMa, rising), lt(r, p(pr, "entry")));
        boolean[] out = gt(b.close, Indicators.sma(b.close, pi(pr, "exitMa")));
        return clean(hold(in, out, null, null));
    }
}

class AdaptiveRegimeSwitch extends AbstractStrategy {
    public String name() { return "adaptive_regime_switch"; }
    public String category() { return "hybrid"; }
    public String direction() { return "long_short"; }
    public String description() { return "Trend-follow (EMA cross) when ADX is high, mean-revert (RSI) when it's low."; }
    public Map<String, Double> defaultParams() { return Map.of("adxN", 14.0, "adxMin", 25.0, "fast", 20.0, "slow", 50.0, "rsiN", 14.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] a = Indicators.adx(b, pi(pr, "adxN"));
        double adxMin = p(pr, "adxMin");
        double[] f = Indicators.ema(b.close, pi(pr, "fast"));
        double[] s = Indicators.ema(b.close, pi(pr, "slow"));
        double[] r = Indicators.rsi(b.close, pi(pr, "rsiN"));
        int len = b.size();
        double[] sig = new double[len];
        for (int i = 0; i < len; i++) {
            if (nan(a[0][i])) continue;
            if (a[0][i] >= adxMin) {
                sig[i] = (nan(f[i]) || nan(s[i])) ? Double.NaN : (f[i] > s[i] ? 1 : -1);
            } else if (!nan(r[i])) {
                sig[i] = r[i] < 30 ? 1 : r[i] > 70 ? -1 : 0;
            }
        }
        return clean(sig);
    }
}

class SupertrendRsiCombo extends AbstractStrategy {
    public String name() { return "supertrend_rsi_combo"; }
    public String category() { return "hybrid"; }
    public String direction() { return "long_short"; }
    public String description() { return "Supertrend direction confirmed by RSI on the same side of 50."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 10.0, "mult", 3.0, "rsiN", 14.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] st = Indicators.supertrend(b, pi(pr, "n"), p(pr, "mult"));
        double[] r = Indicators.rsi(b.close, pi(pr, "rsiN"));
        boolean[] up = and(gt(st, 0), gt(r, 50));
        boolean[] dn = and(lt(st, 0), lt(r, 50));
        return clean(regime(up, dn));
    }
}
