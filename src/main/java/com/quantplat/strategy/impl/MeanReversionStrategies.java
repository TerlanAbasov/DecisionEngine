package com.quantplat.strategy.impl;

import com.quantplat.strategy.AbstractStrategy;
import com.quantplat.strategy.BarSeries;
import com.quantplat.strategy.Indicators;
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
