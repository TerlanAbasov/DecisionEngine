package com.quant.finance.decision.strategy.impl;

import com.quant.finance.decision.strategy.AbstractStrategy;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.Indicators;
import java.util.Map;

class MacdRsiCombo extends AbstractStrategy {
    public String name() {
        return "macd_rsi_combo";
    }

    public String category() {
        return "hybrid";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Confluence filter: MACD trend AND RSI regime must agree.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("rsiN", 14.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[][] macd = Indicators.macd(bars.close, 12, 26, 9);
        double[] macdLine = macd[0];
        double[] signalLine = macd[1];
        double[] rsi = Indicators.rsi(bars.close, intParam(params, "rsiN"));
        boolean[] longCondition = and(above(macdLine, signalLine), above(rsi, 50));
        boolean[] shortCondition = and(below(macdLine, signalLine), below(rsi, 50));
        return clean(regime(longCondition, shortCondition));
    }
}

class DualMaAtrStop extends AbstractStrategy {
    public String name() {
        return "dual_ma_atr_stop";
    }

    public String category() {
        return "hybrid";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "EMA cross entry with a chandelier ATR trailing stop for exits.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("fast", 20.0, "slow", 50.0, "atrN", 22.0, "atrMult", 3.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] fastMa = Indicators.ema(bars.close, intParam(params, "fast"));
        double[] slowMa = Indicators.ema(bars.close, intParam(params, "slow"));
        double[] atr = Indicators.atr(bars, intParam(params, "atrN"));
        double atrMultiplier = param(params, "atrMult");
        int length = bars.size();
        double[] position = new double[length];
        int state = 0;
        double peakHigh = Double.NaN;
        for (int i = 1; i < length; i++) {
            boolean bothReady = !Double.isNaN(fastMa[i]) && !Double.isNaN(slowMa[i]);
            boolean crossUp = bothReady && fastMa[i] > slowMa[i] && fastMa[i - 1] <= slowMa[i - 1];
            if (state == 0) {
                if (crossUp) {
                    state = 1;
                    peakHigh = bars.high[i];
                }
            } else {
                peakHigh = Math.max(peakHigh, bars.high[i]);
                double trailingStop = peakHigh - atrMultiplier * (Double.isNaN(atr[i]) ? 0 : atr[i]);
                if (bars.close[i] < trailingStop || (bothReady && fastMa[i] < slowMa[i])) {
                    state = 0;
                    peakHigh = Double.NaN;
                }
            }
            position[i] = state;
        }
        return clean(position);
    }
}

class TripleConfirmation extends AbstractStrategy {
    public String name() {
        return "triple_confirmation";
    }

    public String category() {
        return "hybrid";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "ADX strength + MACD direction + RSI regime must all agree.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("adxN", 14.0, "adxMin", 20.0, "rsiN", 14.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] adxLine = Indicators.adx(bars, intParam(params, "adxN"))[0];
        double[][] macd = Indicators.macd(bars.close, 12, 26, 9);
        double[] macdLine = macd[0];
        double[] signalLine = macd[1];
        double[] rsi = Indicators.rsi(bars.close, intParam(params, "rsiN"));
        boolean[] strongTrend = above(adxLine, param(params, "adxMin"));
        boolean[] longCondition = and(strongTrend, and(above(macdLine, signalLine), above(rsi, 50)));
        boolean[] shortCondition = and(strongTrend, and(below(macdLine, signalLine), below(rsi, 50)));
        return clean(regime(longCondition, shortCondition));
    }
}

class TrendVolumeCombo extends AbstractStrategy {
    public String name() {
        return "trend_volume_combo";
    }

    public String category() {
        return "hybrid";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "EMA trend confirmed by OBV sitting above/below its own EMA.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("fast", 20.0, "slow", 50.0, "obvEma", 20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] fastMa = Indicators.ema(bars.close, intParam(params, "fast"));
        double[] slowMa = Indicators.ema(bars.close, intParam(params, "slow"));
        double[] obv = Indicators.obv(bars);
        double[] obvEma = Indicators.ema(obv, intParam(params, "obvEma"));
        boolean[] longCondition = and(above(fastMa, slowMa), above(obv, obvEma));
        boolean[] shortCondition = and(below(fastMa, slowMa), below(obv, obvEma));
        return clean(regime(longCondition, shortCondition));
    }
}

class BreakoutMomentumCombo extends AbstractStrategy {
    public String name() {
        return "breakout_momentum_combo";
    }

    public String category() {
        return "hybrid";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "Donchian breakout that also carries positive rate-of-change momentum.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("entryN", 20.0, "exitN", 10.0, "rocN", 63.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        boolean[] breakout = above(bars.close, previous(Indicators.rollingMax(bars.high, intParam(params, "entryN"))));
        boolean[] positiveMomentum = above(Indicators.roc(bars.close, intParam(params, "rocN")), 0);
        boolean[] longEntry = and(breakout, positiveMomentum);
        boolean[] longExit = below(bars.close, previous(Indicators.rollingMin(bars.low, intParam(params, "exitN"))));
        return clean(hold(longEntry, longExit, null, null));
    }
}

class RsiPullbackTrendFilter extends AbstractStrategy {
    public String name() {
        return "rsi_pullback_trend_filter";
    }

    public String category() {
        return "hybrid";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "RSI(2) pullback entries, gated by a rising (not just above) 200-SMA.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("trendMa", 200.0, "rsiN", 2.0, "entry", 10.0, "exitMa", 5.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] trendMa = Indicators.sma(bars.close, intParam(params, "trendMa"));
        boolean[] aboveTrendMa = above(bars.close, trendMa);
        boolean[] trendMaRising = above(trendMa, previous(trendMa));
        double[] rsi = Indicators.rsi(bars.close, intParam(params, "rsiN"));
        boolean[] longEntry = and(and(aboveTrendMa, trendMaRising), below(rsi, param(params, "entry")));
        boolean[] longExit = above(bars.close, Indicators.sma(bars.close, intParam(params, "exitMa")));
        return clean(hold(longEntry, longExit, null, null));
    }
}

class AdaptiveRegimeSwitch extends AbstractStrategy {
    public String name() {
        return "adaptive_regime_switch";
    }

    public String category() {
        return "hybrid";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Trend-follow (EMA cross) when ADX is high, mean-revert (RSI) when it's low.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("adxN", 14.0, "adxMin", 25.0, "fast", 20.0, "slow", 50.0, "rsiN", 14.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] adxLine = Indicators.adx(bars, intParam(params, "adxN"))[0];
        double adxMin = param(params, "adxMin");
        double[] fastMa = Indicators.ema(bars.close, intParam(params, "fast"));
        double[] slowMa = Indicators.ema(bars.close, intParam(params, "slow"));
        double[] rsi = Indicators.rsi(bars.close, intParam(params, "rsiN"));
        int length = bars.size();
        double[] signal = new double[length];
        for (int i = 0; i < length; i++) {
            if (Double.isNaN(adxLine[i])) {
                continue;
            }
            if (adxLine[i] >= adxMin) {
                // trending: follow the moving-average cross
                if (Double.isNaN(fastMa[i]) || Double.isNaN(slowMa[i])) {
                    signal[i] = Double.NaN;
                } else {
                    signal[i] = fastMa[i] > slowMa[i] ? 1 : -1;
                }
            } else if (!Double.isNaN(rsi[i])) {
                // ranging: fade RSI extremes
                if (rsi[i] < 30) {
                    signal[i] = 1;
                } else if (rsi[i] > 70) {
                    signal[i] = -1;
                } else {
                    signal[i] = 0;
                }
            }
        }
        return clean(signal);
    }
}

class SupertrendRsiCombo extends AbstractStrategy {
    public String name() {
        return "supertrend_rsi_combo";
    }

    public String category() {
        return "hybrid";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Supertrend direction confirmed by RSI on the same side of 50.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 10.0, "mult", 3.0, "rsiN", 14.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] trendDirection = Indicators.supertrend(bars, intParam(params, "n"), param(params, "mult"));
        double[] rsi = Indicators.rsi(bars.close, intParam(params, "rsiN"));
        boolean[] longCondition = and(above(trendDirection, 0), above(rsi, 50));
        boolean[] shortCondition = and(below(trendDirection, 0), below(rsi, 50));
        return clean(regime(longCondition, shortCondition));
    }
}
