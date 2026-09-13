package com.quant.finance.decision.strategy.impl;

import com.quant.finance.decision.strategy.AbstractStrategy;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.Indicators;
import java.util.Map;

class Donchian extends AbstractStrategy {
    public String name() { return "donchian"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_only"; }
    public String description() { return "Donchian channel breakout (Turtle 20/10): enter 20-high, exit 10-low."; }
    public Map<String, Double> defaultParams() { return Map.of("entryN", 20.0, "exitN", 10.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        boolean[] in = gt(b.close, shift1(Indicators.rollingMax(b.high, pi(pr, "entryN"))));
        boolean[] out = lt(b.close, shift1(Indicators.rollingMin(b.low, pi(pr, "exitN"))));
        return clean(hold(in, out, null, null));
    }
}

class TurtleSystem extends AbstractStrategy {
    public String name() { return "turtle_system"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "Classic Turtle System-2: 55-day breakout entry, 20-day opposite exit."; }
    public Map<String, Double> defaultParams() { return Map.of("entryN", 55.0, "exitN", 20.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] hi = shift1(Indicators.rollingMax(b.high, pi(pr, "entryN")));
        double[] lo = shift1(Indicators.rollingMin(b.low, pi(pr, "entryN")));
        double[] xhi = shift1(Indicators.rollingMax(b.high, pi(pr, "exitN")));
        double[] xlo = shift1(Indicators.rollingMin(b.low, pi(pr, "exitN")));
        return clean(hold(gt(b.close, hi), lt(b.close, xlo), lt(b.close, lo), gt(b.close, xhi)));
    }
}

class KeltnerBreakout extends AbstractStrategy {
    public String name() { return "keltner_breakout"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "Break of the Keltner channel (EMA +/- ATR)."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "mult", 2.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] kc = Indicators.keltner(b, pi(pr, "n"), p(pr, "mult"));
        return clean(hold(gt(b.close, kc[2]), lt(b.close, kc[1]), lt(b.close, kc[0]), gt(b.close, kc[1])));
    }
}

class AtrChannelBreakout extends AbstractStrategy {
    public String name() { return "atr_channel_breakout"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "SMA +/- k*ATR channel breakout with volatility-scaled bands."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "mult", 2.5); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] mid = Indicators.sma(b.close, pi(pr, "n"));
        double[] atr = Indicators.atr(b, pi(pr, "n"));
        double m = p(pr, "mult");
        double[] up = new double[b.size()], dn = new double[b.size()];
        for (int i = 0; i < b.size(); i++) {
            up[i] = (nan(mid[i]) || nan(atr[i])) ? Double.NaN : mid[i] + m * atr[i];
            dn[i] = (nan(mid[i]) || nan(atr[i])) ? Double.NaN : mid[i] - m * atr[i];
        }
        return clean(hold(gt(b.close, up), lt(b.close, mid), lt(b.close, dn), gt(b.close, mid)));
    }
}

class BollingerSqueeze extends AbstractStrategy {
    public String name() { return "bollinger_squeeze"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "Volatility squeeze then expansion: trade the breakout after low band-width."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "k", 2.0, "squeezePct", 0.25, "lookback", 120.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] bb = Indicators.bollinger(b.close, pi(pr, "n"), p(pr, "k"));
        int lb = pi(pr, "lookback");
        double[] width = new double[b.size()];
        for (int i = 0; i < b.size(); i++)
            width[i] = nan(bb[1][i]) ? Double.NaN : (bb[2][i] - bb[0][i]) / bb[1][i];
        double q = p(pr, "squeezePct");
        boolean[] squeezed = new boolean[b.size()];
        for (int i = lb - 1; i < b.size(); i++) {
            double[] w = new double[lb];
            int c = 0;
            for (int j = i - lb + 1; j <= i; j++) if (!nan(width[j])) w[c++] = width[j];
            if (c == 0) continue;
            double[] slice = java.util.Arrays.copyOf(w, c);
            java.util.Arrays.sort(slice);
            double thresh = slice[(int) Math.floor(q * (c - 1))];
            squeezed[i] = !nan(width[i]) && width[i] <= thresh;
        }
        boolean[] armed = shift1(squeezed);
        boolean[] in = and(armed, gt(b.close, bb[2]));
        boolean[] si = and(armed, lt(b.close, bb[0]));
        return clean(hold(in, lt(b.close, bb[1]), si, gt(b.close, bb[1])));
    }
}

class Nr7Breakout extends AbstractStrategy {
    public String name() { return "nr7_breakout"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "Narrowest-range-7 contraction, trade next-day range breakout."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 7.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = pi(pr, "n"), len = b.size();
        double[] rng = new double[len];
        for (int i = 0; i < len; i++) rng[i] = b.high[i] - b.low[i];
        double[] minRng = Indicators.rollingMin(rng, n);
        boolean[] isNr = new boolean[len];
        double[] nrHi = new double[len], nrLo = new double[len];
        java.util.Arrays.fill(nrHi, Double.NaN);
        java.util.Arrays.fill(nrLo, Double.NaN);
        double lastHi = Double.NaN, lastLo = Double.NaN;
        for (int i = 0; i < len; i++) {
            if (!nan(minRng[i]) && rng[i] == minRng[i]) { isNr[i] = true; lastHi = b.high[i]; lastLo = b.low[i]; }
            nrHi[i] = lastHi;
            nrLo[i] = lastLo;
        }
        boolean[] armed = shift1(isNr);
        boolean[] in = and(armed, gt(b.high, shift1(nrHi)));
        boolean[] si = and(armed, lt(b.low, shift1(nrLo)));
        double[] mid = Indicators.sma(b.close, 5);
        return clean(hold(in, lt(b.close, mid), si, gt(b.close, mid)));
    }
}

class CciBreakout extends AbstractStrategy {
    public String name() { return "cci_breakout"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "CCI breaking +/-100 initiates a held directional trade."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "level", 100.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] c = Indicators.cci(b, pi(pr, "n"));
        double lvl = p(pr, "level");
        return clean(hold(gt(c, lvl), lt(c, 0), lt(c, -lvl), gt(c, 0)));
    }
}

class VortexBreakout extends AbstractStrategy {
    public String name() { return "vortex_breakout"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "Vortex crossover confirmed by ADX trend strength."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 14.0, "adxMin", 20.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] v = Indicators.vortex(b, pi(pr, "n"));
        double[][] a = Indicators.adx(b, pi(pr, "n"));
        boolean[] strong = gt(a[0], p(pr, "adxMin"));
        boolean[] in = and(strong, gt(v[0], v[1]));
        boolean[] si = and(strong, gt(v[1], v[0]));
        return clean(hold(in, lt(v[0], v[1]), si, gt(v[0], v[1])));
    }
}

class DonchianSqueeze extends AbstractStrategy {
    public String name() { return "donchian_squeeze"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "Trade the breakout that follows a contraction in Donchian channel width."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "squeezePct", 0.25, "lookback", 120.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = pi(pr, "n"), lb = pi(pr, "lookback"), len = b.size();
        double[] hi = Indicators.rollingMax(b.high, n), lo = Indicators.rollingMin(b.low, n);
        double[] width = new double[len];
        for (int i = 0; i < len; i++) width[i] = (nan(hi[i]) || b.close[i] == 0) ? Double.NaN : (hi[i] - lo[i]) / b.close[i];
        double q = p(pr, "squeezePct");
        boolean[] squeezed = new boolean[len];
        for (int i = lb - 1; i < len; i++) {
            double[] w = new double[lb];
            int c = 0;
            for (int j = i - lb + 1; j <= i; j++) if (!nan(width[j])) w[c++] = width[j];
            if (c == 0) continue;
            double[] slice = java.util.Arrays.copyOf(w, c);
            java.util.Arrays.sort(slice);
            double thresh = slice[(int) Math.floor(q * (c - 1))];
            squeezed[i] = !nan(width[i]) && width[i] <= thresh;
        }
        boolean[] armed = shift1(squeezed);
        double[] mid = new double[len];
        for (int i = 0; i < len; i++) mid[i] = (nan(hi[i]) || nan(lo[i])) ? Double.NaN : (hi[i] + lo[i]) / 2;
        boolean[] in = and(armed, gt(b.close, hi));
        boolean[] si = and(armed, lt(b.close, lo));
        return clean(hold(in, lt(b.close, mid), si, gt(b.close, mid)));
    }
}

class VolatilityExpansionBreakout extends AbstractStrategy {
    public String name() { return "volatility_expansion_breakout"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "Trade the direction of a sharp jump in realised volatility."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 10.0, "baseN", 60.0, "mult", 1.8, "exitMa", 10.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int len = b.size();
        double[] ret = new double[len];
        for (int i = 1; i < len; i++) ret[i] = b.close[i] / b.close[i - 1] - 1;
        double[] rv = Indicators.stddev(ret, pi(pr, "n"));
        double[] base = Indicators.sma(rv, pi(pr, "baseN"));
        double mult = p(pr, "mult");
        boolean[] expanding = new boolean[len];
        for (int i = 0; i < len; i++) expanding[i] = !nan(rv[i]) && !nan(base[i]) && rv[i] > base[i] * mult;
        boolean[] up = gt(b.close, shift1(b.close));
        boolean[] in = and(expanding, up);
        boolean[] si = and(expanding, lt(b.close, shift1(b.close)));
        double[] exitMa = Indicators.sma(b.close, pi(pr, "exitMa"));
        return clean(hold(in, lt(b.close, exitMa), si, gt(b.close, exitMa)));
    }
}

class PivotPointBreakout extends AbstractStrategy {
    public String name() { return "pivot_point_breakout"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "Classic floor-trader pivot: trade a close beyond yesterday's R1/S1."; }
    public Map<String, Double> defaultParams() { return Map.of("exitMa", 10.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int len = b.size();
        double[] r1 = new double[len], s1 = new double[len];
        for (int i = 1; i < len; i++) {
            double piv = (b.high[i - 1] + b.low[i - 1] + b.close[i - 1]) / 3;
            r1[i] = 2 * piv - b.low[i - 1];
            s1[i] = 2 * piv - b.high[i - 1];
        }
        double[] exitMa = Indicators.sma(b.close, pi(pr, "exitMa"));
        boolean[] in = gt(b.close, r1), si = lt(b.close, s1);
        return clean(hold(in, lt(b.close, exitMa), si, gt(b.close, exitMa)));
    }
}

class CamarillaBreakout extends AbstractStrategy {
    public String name() { return "camarilla_breakout"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "Camarilla pivots: trade a close beyond the R3/S3 breakout levels."; }
    public Map<String, Double> defaultParams() { return Map.of("exitMa", 10.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int len = b.size();
        double[] r3 = new double[len], s3 = new double[len];
        for (int i = 1; i < len; i++) {
            double range = b.high[i - 1] - b.low[i - 1];
            r3[i] = b.close[i - 1] + range * 1.1 / 4;
            s3[i] = b.close[i - 1] - range * 1.1 / 4;
        }
        double[] exitMa = Indicators.sma(b.close, pi(pr, "exitMa"));
        boolean[] in = gt(b.close, r3), si = lt(b.close, s3);
        return clean(hold(in, lt(b.close, exitMa), si, gt(b.close, exitMa)));
    }
}

class RangeExpansionBreakout extends AbstractStrategy {
    public String name() { return "range_expansion_breakout"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "A single bar's range far exceeding its ATR signals a breakout day."; }
    public Map<String, Double> defaultParams() { return Map.of("atrN", 14.0, "mult", 1.5, "exitMa", 10.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] atr = Indicators.atr(b, pi(pr, "atrN"));
        double mult = p(pr, "mult");
        int len = b.size();
        boolean[] wide = new boolean[len];
        for (int i = 0; i < len; i++) wide[i] = !nan(atr[i]) && (b.high[i] - b.low[i]) > atr[i] * mult;
        boolean[] up = gt(b.close, b.open), dn = lt(b.close, b.open);
        boolean[] in = and(wide, up), si = and(wide, dn);
        double[] exitMa = Indicators.sma(b.close, pi(pr, "exitMa"));
        return clean(hold(in, lt(b.close, exitMa), si, gt(b.close, exitMa)));
    }
}

class ThreeBarBreakout extends AbstractStrategy {
    public String name() { return "three_bar_breakout"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "Break of the prior 3 bars' high/low — a short-lookback breakout."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 3.0, "exitMa", 5.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = pi(pr, "n");
        double[] hi = shift1(Indicators.rollingMax(b.high, n));
        double[] lo = shift1(Indicators.rollingMin(b.low, n));
        double[] exitMa = Indicators.sma(b.close, pi(pr, "exitMa"));
        return clean(hold(gt(b.close, hi), lt(b.close, exitMa), lt(b.close, lo), gt(b.close, exitMa)));
    }
}

class MacdZeroCross extends AbstractStrategy {
    public String name() { return "macd_zero_cross"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "MACD line crossing its own zero line (distinct from a signal-line cross)."; }
    public Map<String, Double> defaultParams() { return Map.of("fast", 12.0, "slow", 26.0, "signal", 9.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] m = Indicators.macd(b.close, pi(pr, "fast"), pi(pr, "slow"), pi(pr, "signal"));
        return clean(regime(gt(m[0], 0), lt(m[0], 0)));
    }
}

class LinregChannelBreakout extends AbstractStrategy {
    public String name() { return "linreg_channel_breakout"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "Breakout of a rolling linear-regression channel (fit +/- k*stddev)."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 30.0, "mult", 2.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = pi(pr, "n"), len = b.size();
        double[][] lr = Indicators.linreg(b.close, n);
        double[] resid = new double[len];
        for (int i = 0; i < len; i++) resid[i] = nan(lr[0][i]) ? Double.NaN : b.close[i] - lr[0][i];
        double[] sd = Indicators.stddev(resid, n);
        double mult = p(pr, "mult");
        double[] up = new double[len], dn = new double[len];
        for (int i = 0; i < len; i++) {
            up[i] = (nan(lr[0][i]) || nan(sd[i])) ? Double.NaN : lr[0][i] + mult * sd[i];
            dn[i] = (nan(lr[0][i]) || nan(sd[i])) ? Double.NaN : lr[0][i] - mult * sd[i];
        }
        return clean(hold(gt(b.close, up), lt(b.close, lr[0]), lt(b.close, dn), gt(b.close, lr[0])));
    }
}
