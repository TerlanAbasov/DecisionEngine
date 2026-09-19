package com.quant.finance.decision.strategy.impl;

import com.quant.finance.decision.strategy.AbstractStrategy;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.Indicators;
import java.util.Map;

class Rsi2 extends AbstractStrategy {
    public String name() { return "rsi2"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_only"; }
    public String description() { return "Connors RSI(2): buy pullbacks above the 200-SMA, exit on strength."; }
    public Map<String, Double> defaultParams() { return Map.of("trendMa", 200.0, "rsiN", 2.0, "entry", 10.0, "exitMa", 5.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        boolean[] up = gt(b.close, Indicators.sma(b.close, pi(pr, "trendMa")));
        double[] r = Indicators.rsi(b.close, pi(pr, "rsiN"));
        boolean[] in = and(up, lt(r, p(pr, "entry")));
        boolean[] out = gt(b.close, Indicators.sma(b.close, pi(pr, "exitMa")));
        return clean(hold(in, out, null, null));
    }
}

class Rsi14 extends AbstractStrategy {
    public String name() { return "rsi14"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "Classic RSI(14) oversold/overbought reversion."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 14.0, "low", 30.0, "high", 70.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] r = Indicators.rsi(b.close, pi(pr, "n"));
        return clean(hold(lt(r, p(pr, "low")), gt(r, 50), gt(r, p(pr, "high")), lt(r, 50)));
    }
}

class BollingerReversion extends AbstractStrategy {
    public String name() { return "bollinger_reversion"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "Fade touches of the outer Bollinger Bands back to the mean."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "k", 2.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] bb = Indicators.bollinger(b.close, pi(pr, "n"), p(pr, "k"));
        return clean(hold(lt(b.close, bb[0]), gt(b.close, bb[1]), gt(b.close, bb[2]), lt(b.close, bb[1])));
    }
}

class ZScoreReversion extends AbstractStrategy {
    public String name() { return "zscore_reversion"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "Trade the rolling z-score of price back toward zero."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "entry", 2.0, "exit", 0.5); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] z = Indicators.zscore(b.close, pi(pr, "n"));
        double e = p(pr, "entry"), x = p(pr, "exit");
        return clean(hold(lt(z, -e), gt(z, -x), gt(z, e), lt(z, x)));
    }
}

class WilliamsRStrat extends AbstractStrategy {
    public String name() { return "williams_r"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "Williams %R extremes reversion."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 14.0, "low", -80.0, "high", -20.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] w = Indicators.williamsR(b, pi(pr, "n"));
        return clean(hold(lt(w, p(pr, "low")), gt(w, -50), gt(w, p(pr, "high")), lt(w, -50)));
    }
}

class StochReversion extends AbstractStrategy {
    public String name() { return "stochastic"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "Stochastic %K/%D crossovers from oversold/overbought zones."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 14.0, "d", 3.0, "low", 20.0, "high", 80.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] st = Indicators.stochastic(b, pi(pr, "n"), pi(pr, "d"));
        boolean[] in = and(lt(st[0], p(pr, "low")), gt(st[0], st[1]));
        boolean[] si = and(gt(st[0], p(pr, "high")), lt(st[0], st[1]));
        return clean(hold(in, gt(st[0], 50), si, lt(st[0], 50)));
    }
}

class CciReversion extends AbstractStrategy {
    public String name() { return "cci_reversion"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "CCI extremes fade back toward zero."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "low", -100.0, "high", 100.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] c = Indicators.cci(b, pi(pr, "n"));
        return clean(hold(lt(c, p(pr, "low")), gt(c, 0), gt(c, p(pr, "high")), lt(c, 0)));
    }
}

class MfiReversion extends AbstractStrategy {
    public String name() { return "mfi_reversion"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "Money Flow Index oversold/overbought reversion."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 14.0, "low", 20.0, "high", 80.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] m = Indicators.mfi(b, pi(pr, "n"));
        return clean(hold(lt(m, p(pr, "low")), gt(m, 50), gt(m, p(pr, "high")), lt(m, 50)));
    }
}

class CmoReversion extends AbstractStrategy {
    public String name() { return "cmo_reversion"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "Chande Momentum Oscillator extremes fade."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 14.0, "low", -50.0, "high", 50.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] c = Indicators.cmo(b.close, pi(pr, "n"));
        return clean(hold(lt(c, p(pr, "low")), gt(c, 0), gt(c, p(pr, "high")), lt(c, 0)));
    }
}

class BollingerPctB extends AbstractStrategy {
    public String name() { return "bollinger_pctb"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "Bollinger %b (position within the bands) extremes fade."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "k", 2.0, "low", 0.05, "high", 0.95); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] bb = Indicators.bollinger(b.close, pi(pr, "n"), p(pr, "k"));
        int len = b.size();
        double[] pctB = new double[len];
        for (int i = 0; i < len; i++) {
            double range = bb[2][i] - bb[0][i];
            pctB[i] = (nan(bb[2][i]) || range == 0) ? Double.NaN : (b.close[i] - bb[0][i]) / range;
        }
        return clean(hold(lt(pctB, p(pr, "low")), gt(pctB, 0.5), gt(pctB, p(pr, "high")), lt(pctB, 0.5)));
    }
}

class Rsi21 extends AbstractStrategy {
    public String name() { return "rsi21"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "Slower RSI(21) oversold/overbought reversion, fewer whipsaws than RSI(14)."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 21.0, "low", 35.0, "high", 65.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] r = Indicators.rsi(b.close, pi(pr, "n"));
        return clean(hold(lt(r, p(pr, "low")), gt(r, 50), gt(r, p(pr, "high")), lt(r, 50)));
    }
}

class Rsi2Extreme extends AbstractStrategy {
    public String name() { return "rsi2_extreme"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "RSI(2) at extreme readings, traded both long and short (no trend filter)."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 2.0, "low", 5.0, "high", 95.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] r = Indicators.rsi(b.close, pi(pr, "n"));
        return clean(hold(lt(r, p(pr, "low")), gt(r, 50), gt(r, p(pr, "high")), lt(r, 50)));
    }
}

class DpoReversion extends AbstractStrategy {
    public String name() { return "dpo_reversion"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "Detrended Price Oscillator: fade stretches away from the cycle mean."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "band", 0.05); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] d = Indicators.dpo(b.close, pi(pr, "n"));
        int len = b.size();
        double[] pct = new double[len];
        for (int i = 0; i < len; i++) pct[i] = (nan(d[i]) || b.close[i] == 0) ? Double.NaN : d[i] / b.close[i];
        double band = p(pr, "band");
        return clean(hold(lt(pct, -band), gt(pct, 0), gt(pct, band), lt(pct, 0)));
    }
}

class KeltnerReversion extends AbstractStrategy {
    public String name() { return "keltner_reversion"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "Fade touches of the Keltner channel bands back toward the midline."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "mult", 2.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] kc = Indicators.keltner(b, pi(pr, "n"), p(pr, "mult"));
        return clean(hold(lt(b.close, kc[0]), gt(b.close, kc[1]), gt(b.close, kc[2]), lt(b.close, kc[1])));
    }
}

class VwapBandReversion extends AbstractStrategy {
    public String name() { return "vwap_band_reversion"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "Symmetric fade of both VWAP bands, long and short."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "band", 0.03); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] v = Indicators.vwap(b, pi(pr, "n"));
        double band = p(pr, "band");
        int len = b.size();
        double[] lower = new double[len], upper = new double[len];
        for (int i = 0; i < len; i++) {
            lower[i] = nan(v[i]) ? Double.NaN : v[i] * (1 - band);
            upper[i] = nan(v[i]) ? Double.NaN : v[i] * (1 + band);
        }
        return clean(hold(lt(b.close, lower), gt(b.close, v), gt(b.close, upper), lt(b.close, v)));
    }
}

class AtrBandReversion extends AbstractStrategy {
    public String name() { return "atr_band_reversion"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "Fade a k*ATR excursion from the SMA back toward the mean."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "mult", 2.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] mid = Indicators.sma(b.close, pi(pr, "n"));
        double[] atr = Indicators.atr(b, pi(pr, "n"));
        double m = p(pr, "mult");
        int len = b.size();
        double[] up = new double[len], dn = new double[len];
        for (int i = 0; i < len; i++) {
            up[i] = (nan(mid[i]) || nan(atr[i])) ? Double.NaN : mid[i] + m * atr[i];
            dn[i] = (nan(mid[i]) || nan(atr[i])) ? Double.NaN : mid[i] - m * atr[i];
        }
        return clean(hold(lt(b.close, dn), gt(b.close, mid), gt(b.close, up), lt(b.close, mid)));
    }
}

class UltimateOscillatorReversion extends AbstractStrategy {
    public String name() { return "ultimate_oscillator"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "Williams' Ultimate Oscillator extremes reversion."; }
    public Map<String, Double> defaultParams() { return Map.of("n1", 7.0, "n2", 14.0, "n3", 28.0, "low", 30.0, "high", 70.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] u = Indicators.ultimateOscillator(b, pi(pr, "n1"), pi(pr, "n2"), pi(pr, "n3"));
        return clean(hold(lt(u, p(pr, "low")), gt(u, 50), gt(u, p(pr, "high")), lt(u, 50)));
    }
}

class StochRsi extends AbstractStrategy {
    public String name() { return "stoch_rsi"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "Stochastic oscillator applied to RSI (StochRSI) extremes."; }
    public Map<String, Double> defaultParams() { return Map.of("rsiN", 14.0, "stochN", 14.0, "low", 20.0, "high", 80.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] r = Indicators.rsi(b.close, pi(pr, "rsiN"));
        int n = pi(pr, "stochN"), len = b.size();
        double[] hi = Indicators.rollingMax(r, n), lo = Indicators.rollingMin(r, n);
        double[] k = new double[len];
        for (int i = 0; i < len; i++) {
            double range = hi[i] - lo[i];
            k[i] = (nan(hi[i]) || range == 0) ? Double.NaN : 100 * (r[i] - lo[i]) / range;
        }
        return clean(hold(lt(k, p(pr, "low")), gt(k, 50), gt(k, p(pr, "high")), lt(k, 50)));
    }
}

class WaveTrend extends AbstractStrategy {
    public String name() { return "wave_trend"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() { return "LazyBear's WaveTrend oscillator: WT1/WT2 signal-line cross gated by overbought/oversold zones."; }
    public Map<String, Double> defaultParams() {
        return Map.of("channelLen", 10.0, "avgLen", 21.0, "obLevel", 60.0, "osLevel", -60.0);
    }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[][] wt = Indicators.waveTrend(b, pi(pr, "channelLen"), pi(pr, "avgLen"));
        double[] wt1 = wt[0], wt2 = wt[1];
        double ob = p(pr, "obLevel"), os = p(pr, "osLevel");
        return clean(hold(
                and(gt(wt1, wt2), lt(wt2, os)),   // long: bullish cross while oversold
                gt(wt1, ob),                      // exit long: reached overbought
                and(lt(wt1, wt2), gt(wt2, ob)),   // short: bearish cross while overbought
                lt(wt1, os)));                    // exit short: reached oversold
    }
}

class SupportResistanceBounce extends AbstractStrategy {
    public String name() { return "support_resistance_bounce"; }
    public String category() { return "mean_reversion"; }
    public String direction() { return "long_short"; }
    public String description() {
        return "Fade a rolling swing-high resistance / swing-low support: buy the bounce off "
             + "support, sell the decline off resistance, exit at the midpoint.";
    }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "bufferPct", 15.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = pi(pr, "n"), len = b.size();
        double bufPct = p(pr, "bufferPct") / 100.0;
        double[] resistance = Indicators.rollingMax(b.high, n);
        double[] support = Indicators.rollingMin(b.low, n);
        double[] mid = new double[len], supportZone = new double[len], resistZone = new double[len];
        for (int i = 0; i < len; i++) {
            if (nan(resistance[i]) || nan(support[i])) {
                mid[i] = supportZone[i] = resistZone[i] = Double.NaN;
                continue;
            }
            double range = resistance[i] - support[i];
            mid[i] = (resistance[i] + support[i]) / 2.0;
            supportZone[i] = support[i] + bufPct * range;      // "at/near support"
            resistZone[i] = resistance[i] - bufPct * range;    // "at/near resistance"
        }
        return clean(hold(
                lt(b.close, supportZone),   // buy: price back near support
                gt(b.close, mid),           // exit long: reverted to the midpoint
                gt(b.close, resistZone),    // sell: price back near resistance
                lt(b.close, mid)));         // exit short: reverted to the midpoint
    }
}
