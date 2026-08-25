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
