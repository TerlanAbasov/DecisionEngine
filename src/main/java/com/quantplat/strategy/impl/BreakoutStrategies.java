package com.quantplat.strategy.impl;

import com.quantplat.strategy.AbstractStrategy;
import com.quantplat.strategy.BarSeries;
import com.quantplat.strategy.Indicators;
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
