package com.quantplat.strategy.impl;

import com.quantplat.strategy.AbstractStrategy;
import com.quantplat.strategy.BarSeries;
import com.quantplat.strategy.Indicators;
import java.util.Map;

class ObvTrend extends AbstractStrategy {
    public String name() { return "obv_trend"; }
    public String category() { return "volume"; }
    public String direction() { return "long_short"; }
    public String description() { return "On-Balance-Volume vs its EMA — accumulation/distribution."; }
    public Map<String, Double> defaultParams() { return Map.of("emaN", 20.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] o = Indicators.obv(b);
        double[] oe = Indicators.ema(o, pi(pr, "emaN"));
        return clean(regime(gt(o, oe), lt(o, oe)));
    }
}

class VwapReversion extends AbstractStrategy {
    public String name() { return "vwap_reversion"; }
    public String category() { return "volume"; }
    public String direction() { return "long_only"; }
    public String description() { return "Buy dips a set distance below rolling VWAP, exit back at VWAP."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "band", 0.03); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] v = Indicators.vwap(b, pi(pr, "n"));
        double band = p(pr, "band");
        double[] lower = new double[b.size()];
        for (int i = 0; i < b.size(); i++) lower[i] = nan(v[i]) ? Double.NaN : v[i] * (1 - band);
        return clean(hold(lt(b.close, lower), gt(b.close, v), null, null));
    }
}

class VolumeSpikeBreakout extends AbstractStrategy {
    public String name() { return "volume_spike_breakout"; }
    public String category() { return "volume"; }
    public String direction() { return "long_only"; }
    public String description() { return "Breakout confirmed by a volume surge above its average."; }
    public Map<String, Double> defaultParams() { return Map.of("volN", 20.0, "mult", 2.0, "trendMa", 50.0, "exitMa", 10.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] avgV = Indicators.sma(b.volume, pi(pr, "volN"));
        double mult = p(pr, "mult");
        boolean[] spike = new boolean[b.size()];
        for (int i = 1; i < b.size(); i++)
            spike[i] = !nan(avgV[i]) && b.volume[i] > avgV[i] * mult && b.close[i] > b.close[i - 1];
        boolean[] in = and(spike, gt(b.close, Indicators.sma(b.close, pi(pr, "trendMa"))));
        boolean[] out = lt(b.close, Indicators.sma(b.close, pi(pr, "exitMa")));
        return clean(hold(in, out, null, null));
    }
}

class MfiTrend extends AbstractStrategy {
    public String name() { return "mfi_trend"; }
    public String category() { return "volume"; }
    public String direction() { return "long_short"; }
    public String description() { return "Money Flow Index as a trend gauge: long above 50, short below."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 14.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] m = Indicators.mfi(b, pi(pr, "n"));
        return clean(regime(gt(m, 50), lt(m, 50)));
    }
}

class ChaikinMoneyFlow extends AbstractStrategy {
    public String name() { return "chaikin_money_flow"; }
    public String category() { return "volume"; }
    public String direction() { return "long_short"; }
    public String description() { return "Chaikin Money Flow sign: accumulation vs distribution pressure."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] c = Indicators.cmf(b, pi(pr, "n"));
        return clean(regime(gt(c, 0), lt(c, 0)));
    }
}

class EaseOfMovement extends AbstractStrategy {
    public String name() { return "ease_of_movement"; }
    public String category() { return "volume"; }
    public String direction() { return "long_short"; }
    public String description() { return "Ease of Movement: price advancing on low volume vs the reverse."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 14.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] e = Indicators.emv(b, pi(pr, "n"));
        return clean(regime(gt(e, 0), lt(e, 0)));
    }
}

class AccumDistribution extends AbstractStrategy {
    public String name() { return "accum_distribution"; }
    public String category() { return "volume"; }
    public String direction() { return "long_short"; }
    public String description() { return "Accumulation/Distribution line versus its EMA."; }
    public Map<String, Double> defaultParams() { return Map.of("emaN", 20.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] ad = Indicators.adLine(b);
        double[] adEma = Indicators.ema(ad, pi(pr, "emaN"));
        return clean(regime(gt(ad, adEma), lt(ad, adEma)));
    }
}

class VolumePriceTrend extends AbstractStrategy {
    public String name() { return "volume_price_trend"; }
    public String category() { return "volume"; }
    public String direction() { return "long_short"; }
    public String description() { return "Volume Price Trend line versus its EMA."; }
    public Map<String, Double> defaultParams() { return Map.of("emaN", 20.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] vpt = Indicators.vpt(b);
        double[] vptEma = Indicators.ema(vpt, pi(pr, "emaN"));
        return clean(regime(gt(vpt, vptEma), lt(vpt, vptEma)));
    }
}

class RelativeVolumeZscore extends AbstractStrategy {
    public String name() { return "relative_volume_zscore"; }
    public String category() { return "volume"; }
    public String direction() { return "long_only"; }
    public String description() { return "Buy an up-close on a volume z-score spike; exit back below trend."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 20.0, "entry", 2.0, "exitMa", 10.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] z = Indicators.zscore(b.volume, pi(pr, "n"));
        boolean[] up = gt(b.close, shift1(b.close));
        boolean[] in = and(gt(z, p(pr, "entry")), up);
        double[] exitMa = Indicators.sma(b.close, pi(pr, "exitMa"));
        return clean(hold(in, lt(b.close, exitMa), null, null));
    }
}

class VwmaVolumeConfirm extends AbstractStrategy {
    public String name() { return "vwma_volume_cross"; }
    public String category() { return "volume"; }
    public String direction() { return "long_short"; }
    public String description() { return "VWMA cross confirmed by above-average volume on the flip bar."; }
    public Map<String, Double> defaultParams() { return Map.of("fast", 10.0, "slow", 30.0, "volN", 20.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] f = Indicators.vwma(b, pi(pr, "fast"));
        double[] s = Indicators.vwma(b, pi(pr, "slow"));
        double[] avgV = Indicators.sma(b.volume, pi(pr, "volN"));
        int len = b.size();
        boolean[] hiVol = new boolean[len];
        for (int i = 0; i < len; i++) hiVol[i] = !nan(avgV[i]) && b.volume[i] > avgV[i];
        boolean[] crossUp = and(gt(f, s), shift1(lt(f, s)));
        boolean[] crossDn = and(lt(f, s), shift1(gt(f, s)));
        boolean[] in = and(crossUp, hiVol), si = and(crossDn, hiVol);
        return clean(hold(in, lt(f, s), si, gt(f, s)));
    }
}
