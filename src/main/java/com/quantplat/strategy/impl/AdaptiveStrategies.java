package com.quantplat.strategy.impl;

import com.quantplat.strategy.AbstractStrategy;
import com.quantplat.strategy.BarSeries;
import com.quantplat.strategy.Indicators;

import java.util.Map;

/**
 * Ten additional, well-known systematic strategies layered on the existing indicator
 * toolbox — adaptive moving averages, Ichimoku, Heikin-Ashi, ConnorsRSI, QQE, the
 * Alligator, the TTM squeeze, fractal breakouts, a choppiness-gated trend and RVI.
 */
final class AdaptiveShared {
    private AdaptiveShared() {}

    static double[] mid2(double[] a, double[] b) {
        double[] o = new double[a.length];
        for (int i = 0; i < a.length; i++)
            o[i] = (Double.isNaN(a[i]) || Double.isNaN(b[i])) ? Double.NaN : (a[i] + b[i]) / 2;
        return o;
    }

    /** Shift a series forward by {@code k} bars (out[i] = a[i-k]); the leading k slots are NaN. */
    static double[] shiftFwd(double[] a, int k) {
        double[] o = new double[a.length];
        for (int i = 0; i < a.length; i++) o[i] = i >= k ? a[i - k] : Double.NaN;
        return o;
    }

    /** Symmetric 4-tap weighted average (1,2,2,1)/6 — the RVI smoother. */
    static double[] swma4(double[] a) {
        double[] o = new double[a.length];
        for (int i = 0; i < a.length; i++)
            o[i] = i < 3 ? Double.NaN : (a[i] + 2 * a[i - 1] + 2 * a[i - 2] + a[i - 3]) / 6.0;
        return o;
    }

    static double[] rollSum(double[] a, int n) {
        double[] o = new double[a.length];
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += Double.isNaN(a[i]) ? 0 : a[i];
            if (i >= n) s -= Double.isNaN(a[i - n]) ? 0 : a[i - n];
            o[i] = i >= n - 1 ? s : Double.NaN;
        }
        return o;
    }

    static double[] median(BarSeries b) {
        double[] o = new double[b.size()];
        for (int i = 0; i < o.length; i++) o[i] = (b.high[i] + b.low[i]) / 2;
        return o;
    }
}

class IchimokuCloud extends AbstractStrategy {
    public String name() { return "ichimoku_cloud"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Ichimoku: Tenkan/Kijun cross confirmed by price on the same side of the cloud."; }
    public Map<String, Double> defaultParams() { return Map.of("tenkan", 9.0, "kijun", 26.0, "senkouB", 52.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int t = pi(pr, "tenkan"), k = pi(pr, "kijun"), sb = pi(pr, "senkouB");
        double[] tenkan = AdaptiveShared.mid2(Indicators.rollingMax(b.high, t), Indicators.rollingMin(b.low, t));
        double[] kijun = AdaptiveShared.mid2(Indicators.rollingMax(b.high, k), Indicators.rollingMin(b.low, k));
        double[] spanA = AdaptiveShared.mid2(tenkan, kijun);
        double[] spanB = AdaptiveShared.mid2(Indicators.rollingMax(b.high, sb), Indicators.rollingMin(b.low, sb));
        int n = b.size();
        boolean[] up = new boolean[n], dn = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (nan(spanA[i]) || nan(spanB[i]) || nan(tenkan[i]) || nan(kijun[i])) continue;
            double top = Math.max(spanA[i], spanB[i]), bot = Math.min(spanA[i], spanB[i]);
            up[i] = b.close[i] > top && tenkan[i] > kijun[i];
            dn[i] = b.close[i] < bot && tenkan[i] < kijun[i];
        }
        return clean(regime(up, dn));
    }
}

class HeikinAshiTrend extends AbstractStrategy {
    public String name() { return "heikin_ashi_trend"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Ride runs of same-colour Heikin-Ashi candles."; }
    public Map<String, Double> defaultParams() { return Map.of("confirm", 2.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = b.size(), confirm = Math.max(1, pi(pr, "confirm"));
        double[] haC = new double[n], haO = new double[n];
        for (int i = 0; i < n; i++) {
            haC[i] = (b.open[i] + b.high[i] + b.low[i] + b.close[i]) / 4.0;
            haO[i] = i == 0 ? (b.open[i] + b.close[i]) / 2.0 : (haO[i - 1] + haC[i - 1]) / 2.0;
        }
        boolean[] up = new boolean[n], dn = new boolean[n];
        for (int i = confirm - 1; i < n; i++) {
            boolean allGreen = true, allRed = true;
            for (int j = 0; j < confirm; j++) {
                allGreen &= haC[i - j] > haO[i - j];
                allRed &= haC[i - j] < haO[i - j];
            }
            up[i] = allGreen; dn[i] = allRed;
        }
        return clean(regime(up, dn));
    }
}

class ConnorsRsi extends AbstractStrategy {
    public String name() { return "connors_rsi"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_only"; }
    public String description() { return "ConnorsRSI (RSI + streak-RSI + %-rank): buy the oversold composite, exit on recovery."; }
    public Map<String, Double> defaultParams() {
        return Map.of("rsiLen", 3.0, "streakLen", 2.0, "rankLen", 100.0, "entry", 10.0, "exit", 70.0);
    }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = b.size(), rankLen = pi(pr, "rankLen");
        double[] rsi = Indicators.rsi(b.close, pi(pr, "rsiLen"));
        double[] streak = new double[n];
        for (int i = 1; i < n; i++) {
            if (b.close[i] > b.close[i - 1]) streak[i] = Math.max(1, streak[i - 1] + 1);
            else if (b.close[i] < b.close[i - 1]) streak[i] = Math.min(-1, streak[i - 1] - 1);
            else streak[i] = 0;
        }
        double[] streakRsi = Indicators.rsi(streak, pi(pr, "streakLen"));
        double[] roc1 = Indicators.roc(b.close, 1);
        double[] crsi = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < rankLen || nan(rsi[i]) || nan(streakRsi[i]) || nan(roc1[i])) { crsi[i] = Double.NaN; continue; }
            int less = 0;
            for (int j = i - rankLen; j < i; j++) if (!nan(roc1[j]) && roc1[j] < roc1[i]) less++;
            double pctRank = 100.0 * less / rankLen;
            crsi[i] = (rsi[i] + streakRsi[i] + pctRank) / 3.0;
        }
        boolean[] li = lt(crsi, p(pr, "entry")), lo = gt(crsi, p(pr, "exit"));
        return clean(hold(li, lo, null, null));
    }
}

class KamaTrend extends AbstractStrategy {
    public String name() { return "kama_trend"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Kaufman Adaptive MA: fast in trends, flat in noise — trade price vs KAMA and its slope."; }
    public Map<String, Double> defaultParams() { return Map.of("er", 10.0, "fast", 2.0, "slow", 30.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = b.size(), er = Math.max(2, pi(pr, "er"));
        double scF = 2.0 / (pi(pr, "fast") + 1), scS = 2.0 / (pi(pr, "slow") + 1);
        double[] kama = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < er) { kama[i] = b.close[i]; continue; }
            double change = Math.abs(b.close[i] - b.close[i - er]);
            double vol = 0;
            for (int j = i - er + 1; j <= i; j++) vol += Math.abs(b.close[j] - b.close[j - 1]);
            double efRatio = vol > 0 ? change / vol : 0;
            double sc = Math.pow(efRatio * (scF - scS) + scS, 2);
            kama[i] = kama[i - 1] + sc * (b.close[i] - kama[i - 1]);
        }
        boolean[] up = new boolean[n], dn = new boolean[n];
        for (int i = er + 1; i < n; i++) {
            double slope = kama[i] - kama[i - 1];
            up[i] = b.close[i] > kama[i] && slope > 0;
            dn[i] = b.close[i] < kama[i] && slope < 0;
        }
        return clean(regime(up, dn));
    }
}

class QqeSignal extends AbstractStrategy {
    public String name() { return "qqe_signal"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_short"; }
    public String description() { return "QQE: a smoothed RSI ridden with an ATR-of-RSI trailing stop."; }
    public Map<String, Double> defaultParams() { return Map.of("rsiLen", 14.0, "smooth", 5.0, "factor", 4.236); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = b.size();
        double[] rsiMa = Indicators.ema(Indicators.rsi(b.close, pi(pr, "rsiLen")), pi(pr, "smooth"));
        double[] delta = new double[n];
        for (int i = 1; i < n; i++) delta[i] = nan(rsiMa[i]) || nan(rsiMa[i - 1]) ? 0 : Math.abs(rsiMa[i] - rsiMa[i - 1]);
        double[] band = Indicators.ema(delta, pi(pr, "rsiLen") * 2 - 1);
        double f = p(pr, "factor");
        double[] ts = new double[n];
        boolean[] up = new boolean[n], dn = new boolean[n];
        double prevTs = Double.NaN;
        for (int i = 0; i < n; i++) {
            if (nan(rsiMa[i]) || nan(band[i])) { ts[i] = Double.NaN; continue; }
            double d = band[i] * f;
            if (nan(prevTs)) prevTs = rsiMa[i] - d;
            double next = rsiMa[i] > prevTs ? Math.max(prevTs, rsiMa[i] - d) : Math.min(prevTs, rsiMa[i] + d);
            ts[i] = next; prevTs = next;
            up[i] = rsiMa[i] > next; dn[i] = rsiMa[i] < next;
        }
        return clean(regime(up, dn));
    }
}

class WilliamsAlligator extends AbstractStrategy {
    public String name() { return "williams_alligator"; }
    public String category() { return "trend"; }
    public String direction() { return "long_short"; }
    public String description() { return "Bill Williams Alligator: trade when the displaced SMMA lines (lips/teeth/jaw) fan out."; }
    public Map<String, Double> defaultParams() { return Map.of("jaw", 13.0, "teeth", 8.0, "lips", 5.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] med = AdaptiveShared.median(b);
        double[] jaw = AdaptiveShared.shiftFwd(Indicators.wilder(med, pi(pr, "jaw")), 8);
        double[] teeth = AdaptiveShared.shiftFwd(Indicators.wilder(med, pi(pr, "teeth")), 5);
        double[] lips = AdaptiveShared.shiftFwd(Indicators.wilder(med, pi(pr, "lips")), 3);
        return clean(regime(and(gt(lips, teeth), gt(teeth, jaw)), and(lt(lips, teeth), lt(teeth, jaw))));
    }
}

class TtmSqueeze extends AbstractStrategy {
    public String name() { return "ttm_squeeze"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "TTM squeeze: Bollinger inside Keltner = coiled; fire on release in the momentum direction."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "bbK", 2.0, "kcMult", 1.5); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = b.size(), win = pi(pr, "n");
        double[][] bb = Indicators.bollinger(b.close, win, p(pr, "bbK"));
        double[][] kc = Indicators.keltner(b, win, p(pr, "kcMult"));
        double[] slope = Indicators.linreg(b.close, win)[1];
        boolean[] li = new boolean[n], si = new boolean[n], lo = new boolean[n], so = new boolean[n];
        boolean prevSqueeze = false;
        for (int i = 0; i < n; i++) {
            if (nan(bb[0][i]) || nan(kc[0][i]) || nan(slope[i])) continue;
            boolean squeeze = bb[0][i] > kc[0][i] && bb[2][i] < kc[2][i];
            boolean released = prevSqueeze && !squeeze;
            prevSqueeze = squeeze;
            if (released && slope[i] > 0) li[i] = true;
            if (released && slope[i] < 0) si[i] = true;
            lo[i] = slope[i] < 0;   // exit long once momentum rolls over
            so[i] = slope[i] > 0;
        }
        return clean(hold(li, lo, si, so));
    }
}

class FractalBreakout extends AbstractStrategy {
    public String name() { return "fractal_breakout"; }
    public String category() { return "breakout"; }
    public String direction() { return "long_short"; }
    public String description() { return "Trade breaks of the most recent Williams 5-bar fractal high / low."; }
    public Map<String, Double> defaultParams() { return Map.of(); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = b.size();
        double[] pos = new double[n];
        double lastUp = Double.NaN, lastDn = Double.NaN;
        int state = 0;
        for (int i = 0; i < n; i++) {
            int c = i - 2;   // fractal confirmed 2 bars back
            if (c >= 2 && c + 2 < n) {
                if (b.high[c] > b.high[c - 1] && b.high[c] > b.high[c - 2]
                        && b.high[c] > b.high[c + 1] && b.high[c] > b.high[c + 2]) lastUp = b.high[c];
                if (b.low[c] < b.low[c - 1] && b.low[c] < b.low[c - 2]
                        && b.low[c] < b.low[c + 1] && b.low[c] < b.low[c + 2]) lastDn = b.low[c];
            }
            if (!nan(lastUp) && b.close[i] > lastUp) state = 1;
            else if (!nan(lastDn) && b.close[i] < lastDn) state = -1;
            pos[i] = state;
        }
        return clean(pos);
    }
}

class ChoppinessTrend extends AbstractStrategy {
    public String name() { return "choppiness_trend"; }
    public String category() { return "hybrid"; }
    public String direction() { return "long_short"; }
    public String description() { return "Only take the EMA-cross trend when the Choppiness Index says the market is trending."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 14.0, "fast", 20.0, "slow", 50.0, "chopMax", 38.2); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = b.size(), win = pi(pr, "n");
        double[] tr = Indicators.trueRange(b);
        double[] sumTr = AdaptiveShared.rollSum(tr, win);
        double[] hi = Indicators.rollingMax(b.high, win), lo = Indicators.rollingMin(b.low, win);
        double logN = Math.log10(win);
        double[] ef = Indicators.ema(b.close, pi(pr, "fast")), es = Indicators.ema(b.close, pi(pr, "slow"));
        double chopMax = p(pr, "chopMax");
        boolean[] up = new boolean[n], dn = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (nan(sumTr[i]) || nan(hi[i]) || nan(lo[i]) || nan(ef[i]) || nan(es[i])) continue;
            double range = hi[i] - lo[i];
            if (range <= 0) continue;
            double ci = 100 * Math.log10(sumTr[i] / range) / logN;
            if (ci >= chopMax) continue;   // too choppy — stay flat
            up[i] = ef[i] > es[i];
            dn[i] = ef[i] < es[i];
        }
        return clean(regime(up, dn));
    }
}

class RviSignal extends AbstractStrategy {
    public String name() { return "rvi_signal"; }
    public String category() { return "momentum"; }
    public String direction() { return "long_short"; }
    public String description() { return "Relative Vigor Index: close-vs-open strength normalised by range, traded against its signal line."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 10.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = b.size(), win = pi(pr, "n");
        double[] num0 = new double[n], den0 = new double[n];
        for (int i = 0; i < n; i++) { num0[i] = b.close[i] - b.open[i]; den0[i] = b.high[i] - b.low[i]; }
        double[] numS = AdaptiveShared.rollSum(AdaptiveShared.swma4(num0), win);
        double[] denS = AdaptiveShared.rollSum(AdaptiveShared.swma4(den0), win);
        double[] rvi = new double[n];
        for (int i = 0; i < n; i++)
            rvi[i] = (nan(numS[i]) || nan(denS[i]) || denS[i] == 0) ? Double.NaN : numS[i] / denS[i];
        double[] sig = AdaptiveShared.swma4(rvi);
        return clean(regime(gt(rvi, sig), lt(rvi, sig)));
    }
}
