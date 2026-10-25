package com.quant.finance.decision.strategy.impl;

import com.quant.finance.decision.strategy.AbstractStrategy;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.Indicators;
import java.util.Map;

class SmaCross extends AbstractStrategy {
    public String name() {
        return "sma_cross";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Fast/slow simple-MA crossover (golden/death cross).";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("fast", 50.0, "slow", 200.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] fastMa = Indicators.sma(bars.close, intParam(params, "fast"));
        double[] slowMa = Indicators.sma(bars.close, intParam(params, "slow"));
        return clean(regime(above(fastMa, slowMa), below(fastMa, slowMa)));
    }
}

class EmaCross extends AbstractStrategy {
    public String name() {
        return "ema_cross";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Exponential MA crossover — reacts faster than SMA.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("fast", 20.0, "slow", 50.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] fastMa = Indicators.ema(bars.close, intParam(params, "fast"));
        double[] slowMa = Indicators.ema(bars.close, intParam(params, "slow"));
        return clean(regime(above(fastMa, slowMa), below(fastMa, slowMa)));
    }
}

class MacdTrend extends AbstractStrategy {
    public String name() {
        return "macd";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Trade the MACD line versus its signal line.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("fast", 12.0, "slow", 26.0, "signal", 9.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[][] macd = Indicators.macd(
                bars.close, intParam(params, "fast"), intParam(params, "slow"), intParam(params, "signal"));
        double[] macdLine = macd[0];
        double[] signalLine = macd[1];
        return clean(regime(above(macdLine, signalLine), below(macdLine, signalLine)));
    }
}

class TripleMa extends AbstractStrategy {
    public String name() {
        return "triple_ma";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Long when fast>mid>slow (stacked up), short when stacked down.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("fast", 10.0, "mid", 30.0, "slow", 60.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] fastMa = Indicators.ema(bars.close, intParam(params, "fast"));
        double[] midMa = Indicators.ema(bars.close, intParam(params, "mid"));
        double[] slowMa = Indicators.ema(bars.close, intParam(params, "slow"));
        boolean[] stackedUp = and(above(fastMa, midMa), above(midMa, slowMa));
        boolean[] stackedDown = and(below(fastMa, midMa), below(midMa, slowMa));
        return clean(regime(stackedUp, stackedDown));
    }
}

class AdxTrend extends AbstractStrategy {
    public String name() {
        return "adx_trend";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Only trade when ADX confirms a trend; direction from +DI/-DI.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 14.0, "adxMin", 25.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[][] adx = Indicators.adx(bars, intParam(params, "n"));
        double[] adxLine = adx[0];
        double[] plusDI = adx[1];
        double[] minusDI = adx[2];
        boolean[] strongTrend = above(adxLine, param(params, "adxMin"));
        return clean(regime(and(strongTrend, above(plusDI, minusDI)), and(strongTrend, above(minusDI, plusDI))));
    }
}

class SupertrendStrat extends AbstractStrategy {
    public String name() {
        return "supertrend";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "ATR-based Supertrend: follow the flip of the trend line.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 10.0, "mult", 3.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] trendDirection = Indicators.supertrend(bars, intParam(params, "n"), param(params, "mult"));
        return clean(regime(above(trendDirection, 0), below(trendDirection, 0)));
    }
}

class KalmanTrend extends AbstractStrategy {
    public String name() {
        return "kalman_trend";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Kalman-filtered price trend; trade slope + price cross.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("q", 0.0001, "r", 0.01);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] trendLine = Indicators.kalman(bars.close, param(params, "q"), param(params, "r"));
        double[] slope = new double[trendLine.length];
        for (int i = 1; i < trendLine.length; i++) {
            slope[i] = trendLine[i] - trendLine[i - 1];
        }
        boolean[] longCondition = and(above(bars.close, trendLine), above(slope, 0));
        boolean[] shortCondition = and(below(bars.close, trendLine), below(slope, 0));
        return clean(regime(longCondition, shortCondition));
    }
}

class HullMaTrend extends AbstractStrategy {
    public String name() {
        return "hull_ma_trend";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Price vs Hull Moving Average — a faster, lower-lag trend filter.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 21.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] hullMa = Indicators.hullMa(bars.close, intParam(params, "n"));
        return clean(regime(above(bars.close, hullMa), below(bars.close, hullMa)));
    }
}

class DemaCross extends AbstractStrategy {
    public String name() {
        return "dema_cross";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Double-EMA crossover — less lag than a plain EMA cross.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("fast", 12.0, "slow", 26.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] fastMa = Indicators.dema(bars.close, intParam(params, "fast"));
        double[] slowMa = Indicators.dema(bars.close, intParam(params, "slow"));
        return clean(regime(above(fastMa, slowMa), below(fastMa, slowMa)));
    }
}

class TemaCross extends AbstractStrategy {
    public String name() {
        return "tema_cross";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Triple-EMA crossover — even less lag, more whipsaw-prone.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("fast", 9.0, "slow", 21.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] fastMa = Indicators.tema(bars.close, intParam(params, "fast"));
        double[] slowMa = Indicators.tema(bars.close, intParam(params, "slow"));
        return clean(regime(above(fastMa, slowMa), below(fastMa, slowMa)));
    }
}

class VwmaTrend extends AbstractStrategy {
    public String name() {
        return "vwma_trend";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Price vs volume-weighted moving average trend filter.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] vwma = Indicators.vwma(bars, intParam(params, "n"));
        return clean(regime(above(bars.close, vwma), below(bars.close, vwma)));
    }
}

class AroonTrend extends AbstractStrategy {
    public String name() {
        return "aroon_trend";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Aroon up/down: trade the stronger, dominant side above a strength floor.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 25.0, "floor", 70.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[][] aroon = Indicators.aroon(bars, intParam(params, "n"));
        double[] aroonUp = aroon[0];
        double[] aroonDown = aroon[1];
        double floor = param(params, "floor");
        boolean[] longCondition = and(above(aroonUp, aroonDown), above(aroonUp, floor));
        boolean[] shortCondition = and(above(aroonDown, aroonUp), above(aroonDown, floor));
        return clean(regime(longCondition, shortCondition));
    }
}

class VortexTrend extends AbstractStrategy {
    public String name() {
        return "vortex_trend";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Vortex indicator: trade the VI+/VI- crossover.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 14.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[][] vortex = Indicators.vortex(bars, intParam(params, "n"));
        double[] plusVortex = vortex[0];
        double[] minusVortex = vortex[1];
        return clean(regime(above(plusVortex, minusVortex), below(plusVortex, minusVortex)));
    }
}

class TrixSignal extends AbstractStrategy {
    public String name() {
        return "trix_signal";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Triple-smoothed EMA rate of change (TRIX) versus its zero line.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 15.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] trix = Indicators.trix(bars.close, intParam(params, "n"));
        return clean(regime(above(trix, 0), below(trix, 0)));
    }
}

class LinregSlope extends AbstractStrategy {
    public String name() {
        return "linreg_slope";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Sign of the rolling linear-regression slope of price.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] slope = Indicators.linreg(bars.close, intParam(params, "n"))[1];
        return clean(regime(above(slope, 0), below(slope, 0)));
    }
}

class ParabolicSar extends AbstractStrategy {
    public String name() {
        return "parabolic_sar";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Wilder's Parabolic SAR: follow the flip of the stop-and-reverse dot.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("step", 0.02, "max", 0.2);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] trendDirection = Indicators.psar(bars, param(params, "step"), param(params, "max"));
        return clean(regime(above(trendDirection, 0), below(trendDirection, 0)));
    }
}

class EmaRibbon extends AbstractStrategy {
    public String name() {
        return "ema_ribbon";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "5-EMA ribbon (8/13/21/34/55): long when fully stacked up, short when stacked down.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("e1", 8.0, "e2", 13.0, "e3", 21.0, "e4", 34.0, "e5", 55.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] ema1 = Indicators.ema(bars.close, intParam(params, "e1"));
        double[] ema2 = Indicators.ema(bars.close, intParam(params, "e2"));
        double[] ema3 = Indicators.ema(bars.close, intParam(params, "e3"));
        double[] ema4 = Indicators.ema(bars.close, intParam(params, "e4"));
        double[] ema5 = Indicators.ema(bars.close, intParam(params, "e5"));
        boolean[] stackedUp = and(and(above(ema1, ema2), above(ema2, ema3)), and(above(ema3, ema4), above(ema4, ema5)));
        boolean[] stackedDown =
                and(and(below(ema1, ema2), below(ema2, ema3)), and(below(ema3, ema4), below(ema4, ema5)));
        return clean(regime(stackedUp, stackedDown));
    }
}

class ElderRayTrend extends AbstractStrategy {
    public String name() {
        return "elder_ray";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Elder Ray bull/bear power around a 13-EMA trend filter.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 13.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] ema = Indicators.ema(bars.close, intParam(params, "n"));
        int length = bars.size();
        double[] bullPower = new double[length];
        double[] bearPower = new double[length];
        for (int i = 0; i < length; i++) {
            bullPower[i] = Double.isNaN(ema[i]) ? Double.NaN : bars.high[i] - ema[i];
            bearPower[i] = Double.isNaN(ema[i]) ? Double.NaN : bars.low[i] - ema[i];
        }
        boolean[] longCondition = and(above(bullPower, 0), above(bearPower, 0));
        boolean[] shortCondition = and(below(bullPower, 0), below(bearPower, 0));
        return clean(regime(longCondition, shortCondition));
    }
}

class CoppockCurve extends AbstractStrategy {
    public String name() {
        return "coppock_curve";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Coppock curve: smoothed sum of two long-horizon ROCs versus zero.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("roc1", 14.0, "roc2", 11.0, "smooth", 10.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] roc1 = Indicators.roc(bars.close, intParam(params, "roc1"));
        double[] roc2 = Indicators.roc(bars.close, intParam(params, "roc2"));
        int length = bars.size();
        double[] rocSum = new double[length];
        for (int i = 0; i < length; i++) {
            rocSum[i] = (Double.isNaN(roc1[i]) ? 0 : roc1[i]) + (Double.isNaN(roc2[i]) ? 0 : roc2[i]);
        }
        double[] coppock = Indicators.ema(rocSum, intParam(params, "smooth"));
        return clean(regime(above(coppock, 0), below(coppock, 0)));
    }
}

class DonchianMidline extends AbstractStrategy {
    public String name() {
        return "donchian_midline";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Price versus the midline of an N-day Donchian channel.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int period = intParam(params, "n");
        double[] highestHigh = Indicators.rollingMax(bars.high, period);
        double[] lowestLow = Indicators.rollingMin(bars.low, period);
        int length = bars.size();
        double[] midline = new double[length];
        for (int i = 0; i < length; i++) {
            midline[i] = (Double.isNaN(highestHigh[i]) || Double.isNaN(lowestLow[i]))
                    ? Double.NaN
                    : (highestHigh[i] + lowestLow[i]) / 2;
        }
        return clean(regime(above(bars.close, midline), below(bars.close, midline)));
    }
}
