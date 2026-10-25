package com.quant.finance.decision.strategy.impl;

import com.quant.finance.decision.strategy.AbstractStrategy;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.Indicators;
import java.util.Map;

class ObvTrend extends AbstractStrategy {
    public String name() {
        return "obv_trend";
    }

    public String category() {
        return "volume";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "On-Balance-Volume vs its EMA — accumulation/distribution.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("emaN", 20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] obv = Indicators.obv(bars);
        double[] obvEma = Indicators.ema(obv, intParam(params, "emaN"));
        return clean(regime(above(obv, obvEma), below(obv, obvEma)));
    }
}

class VwapReversion extends AbstractStrategy {
    public String name() {
        return "vwap_reversion";
    }

    public String category() {
        return "volume";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "Buy dips a set distance below rolling VWAP, exit back at VWAP.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "band", 0.03);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] vwap = Indicators.vwap(bars, intParam(params, "n"));
        double band = param(params, "band");
        double[] lowerBand = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            lowerBand[i] = Double.isNaN(vwap[i]) ? Double.NaN : vwap[i] * (1 - band);
        }
        return clean(hold(below(bars.close, lowerBand), above(bars.close, vwap), null, null));
    }
}

class VolumeSpikeBreakout extends AbstractStrategy {
    public String name() {
        return "volume_spike_breakout";
    }

    public String category() {
        return "volume";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "Breakout confirmed by a volume surge above its average.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("volN", 20.0, "mult", 2.0, "trendMa", 50.0, "exitMa", 10.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] averageVolume = Indicators.sma(bars.volume, intParam(params, "volN"));
        double multiplier = param(params, "mult");
        boolean[] volumeSpike = new boolean[bars.size()];
        for (int i = 1; i < bars.size(); i++) {
            volumeSpike[i] = !Double.isNaN(averageVolume[i])
                    && bars.volume[i] > averageVolume[i] * multiplier
                    && bars.close[i] > bars.close[i - 1];
        }
        boolean[] longEntry =
                and(volumeSpike, above(bars.close, Indicators.sma(bars.close, intParam(params, "trendMa"))));
        boolean[] longExit = below(bars.close, Indicators.sma(bars.close, intParam(params, "exitMa")));
        return clean(hold(longEntry, longExit, null, null));
    }
}

class MfiTrend extends AbstractStrategy {
    public String name() {
        return "mfi_trend";
    }

    public String category() {
        return "volume";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Money Flow Index as a trend gauge: long above 50, short below.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 14.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] mfi = Indicators.mfi(bars, intParam(params, "n"));
        return clean(regime(above(mfi, 50), below(mfi, 50)));
    }
}

class ChaikinMoneyFlow extends AbstractStrategy {
    public String name() {
        return "chaikin_money_flow";
    }

    public String category() {
        return "volume";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Chaikin Money Flow sign: accumulation vs distribution pressure.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] cmf = Indicators.cmf(bars, intParam(params, "n"));
        return clean(regime(above(cmf, 0), below(cmf, 0)));
    }
}

class EaseOfMovement extends AbstractStrategy {
    public String name() {
        return "ease_of_movement";
    }

    public String category() {
        return "volume";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Ease of Movement: price advancing on low volume vs the reverse.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 14.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] emv = Indicators.emv(bars, intParam(params, "n"));
        return clean(regime(above(emv, 0), below(emv, 0)));
    }
}

class AccumDistribution extends AbstractStrategy {
    public String name() {
        return "accum_distribution";
    }

    public String category() {
        return "volume";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Accumulation/Distribution line versus its EMA.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("emaN", 20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] ad = Indicators.adLine(bars);
        double[] adEma = Indicators.ema(ad, intParam(params, "emaN"));
        return clean(regime(above(ad, adEma), below(ad, adEma)));
    }
}

class VolumePriceTrend extends AbstractStrategy {
    public String name() {
        return "volume_price_trend";
    }

    public String category() {
        return "volume";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Volume Price Trend line versus its EMA.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("emaN", 20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] vpt = Indicators.vpt(bars);
        double[] vptEma = Indicators.ema(vpt, intParam(params, "emaN"));
        return clean(regime(above(vpt, vptEma), below(vpt, vptEma)));
    }
}

class RelativeVolumeZscore extends AbstractStrategy {
    public String name() {
        return "relative_volume_zscore";
    }

    public String category() {
        return "volume";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "Buy an up-close on a volume z-score spike; exit back below trend.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "entry", 2.0, "exitMa", 10.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] volumeZScore = Indicators.zscore(bars.volume, intParam(params, "n"));
        boolean[] upBar = above(bars.close, previous(bars.close));
        boolean[] longEntry = and(above(volumeZScore, param(params, "entry")), upBar);
        double[] exitMa = Indicators.sma(bars.close, intParam(params, "exitMa"));
        return clean(hold(longEntry, below(bars.close, exitMa), null, null));
    }
}

class VwmaVolumeConfirm extends AbstractStrategy {
    public String name() {
        return "vwma_volume_cross";
    }

    public String category() {
        return "volume";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "VWMA cross confirmed by above-average volume on the flip bar.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("fast", 10.0, "slow", 30.0, "volN", 20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] fastVwma = Indicators.vwma(bars, intParam(params, "fast"));
        double[] slowVwma = Indicators.vwma(bars, intParam(params, "slow"));
        double[] averageVolume = Indicators.sma(bars.volume, intParam(params, "volN"));
        int length = bars.size();
        boolean[] highVolume = new boolean[length];
        for (int i = 0; i < length; i++) {
            highVolume[i] = !Double.isNaN(averageVolume[i]) && bars.volume[i] > averageVolume[i];
        }
        boolean[] crossUp = and(above(fastVwma, slowVwma), previous(below(fastVwma, slowVwma)));
        boolean[] crossDown = and(below(fastVwma, slowVwma), previous(above(fastVwma, slowVwma)));
        boolean[] longEntry = and(crossUp, highVolume);
        boolean[] shortEntry = and(crossDown, highVolume);
        return clean(hold(longEntry, below(fastVwma, slowVwma), shortEntry, above(fastVwma, slowVwma)));
    }
}
