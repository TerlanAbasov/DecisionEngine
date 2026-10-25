package com.quant.finance.decision.strategy.impl;

import com.quant.finance.decision.strategy.AbstractStrategy;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.Indicators;
import java.util.Arrays;
import java.util.Map;

/** Detects volatility squeezes: bars whose width sits in the lowest part of its recent range. */
final class SqueezeDetector {
    private SqueezeDetector() {}

    /**
     * True on each bar whose {@code width} is at or below the {@code percentile} (0..1) of the non-NaN widths
     * over the last {@code lookback} bars.
     */
    static boolean[] squeezed(double[] width, int lookback, double percentile) {
        boolean[] squeezed = new boolean[width.length];
        for (int i = lookback - 1; i < width.length; i++) {
            double[] window = new double[lookback];
            int validCount = 0;
            for (int j = i - lookback + 1; j <= i; j++) {
                if (!Double.isNaN(width[j])) {
                    window[validCount++] = width[j];
                }
            }
            if (validCount == 0) {
                continue;
            }
            double[] sorted = Arrays.copyOf(window, validCount);
            Arrays.sort(sorted);
            double threshold = sorted[(int) Math.floor(percentile * (validCount - 1))];
            squeezed[i] = !Double.isNaN(width[i]) && width[i] <= threshold;
        }
        return squeezed;
    }
}

class Donchian extends AbstractStrategy {
    public String name() {
        return "donchian";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "Donchian channel breakout (Turtle 20/10): enter 20-high, exit 10-low.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("entryN", 20.0, "exitN", 10.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        boolean[] longEntry = above(bars.close, previous(Indicators.rollingMax(bars.high, intParam(params, "entryN"))));
        boolean[] longExit = below(bars.close, previous(Indicators.rollingMin(bars.low, intParam(params, "exitN"))));
        return clean(hold(longEntry, longExit, null, null));
    }
}

class TurtleSystem extends AbstractStrategy {
    public String name() {
        return "turtle_system";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Classic Turtle System-2: 55-day breakout entry, 20-day opposite exit.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("entryN", 55.0, "exitN", 20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] entryHigh = previous(Indicators.rollingMax(bars.high, intParam(params, "entryN")));
        double[] entryLow = previous(Indicators.rollingMin(bars.low, intParam(params, "entryN")));
        double[] exitHigh = previous(Indicators.rollingMax(bars.high, intParam(params, "exitN")));
        double[] exitLow = previous(Indicators.rollingMin(bars.low, intParam(params, "exitN")));
        return clean(hold(
                above(bars.close, entryHigh),
                below(bars.close, exitLow),
                below(bars.close, entryLow),
                above(bars.close, exitHigh)));
    }
}

class KeltnerBreakout extends AbstractStrategy {
    public String name() {
        return "keltner_breakout";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Break of the Keltner channel (EMA +/- ATR).";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "mult", 2.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[][] keltner = Indicators.keltner(bars, intParam(params, "n"), param(params, "mult"));
        double[] lowerBand = keltner[0];
        double[] middleBand = keltner[1];
        double[] upperBand = keltner[2];
        return clean(hold(
                above(bars.close, upperBand),
                below(bars.close, middleBand),
                below(bars.close, lowerBand),
                above(bars.close, middleBand)));
    }
}

class AtrChannelBreakout extends AbstractStrategy {
    public String name() {
        return "atr_channel_breakout";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "SMA +/- k*ATR channel breakout with volatility-scaled bands.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "mult", 2.5);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] middleBand = Indicators.sma(bars.close, intParam(params, "n"));
        double[] atr = Indicators.atr(bars, intParam(params, "n"));
        double multiplier = param(params, "mult");
        double[] upperBand = new double[bars.size()];
        double[] lowerBand = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            boolean warmingUp = Double.isNaN(middleBand[i]) || Double.isNaN(atr[i]);
            upperBand[i] = warmingUp ? Double.NaN : middleBand[i] + multiplier * atr[i];
            lowerBand[i] = warmingUp ? Double.NaN : middleBand[i] - multiplier * atr[i];
        }
        return clean(hold(
                above(bars.close, upperBand),
                below(bars.close, middleBand),
                below(bars.close, lowerBand),
                above(bars.close, middleBand)));
    }
}

class BollingerSqueeze extends AbstractStrategy {
    public String name() {
        return "bollinger_squeeze";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Volatility squeeze then expansion: trade the breakout after low band-width.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "k", 2.0, "squeezePct", 0.25, "lookback", 120.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[][] bollinger = Indicators.bollinger(bars.close, intParam(params, "n"), param(params, "k"));
        double[] lowerBand = bollinger[0];
        double[] middleBand = bollinger[1];
        double[] upperBand = bollinger[2];
        double[] bandWidth = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            bandWidth[i] = Double.isNaN(middleBand[i]) ? Double.NaN : (upperBand[i] - lowerBand[i]) / middleBand[i];
        }
        boolean[] squeezed =
                SqueezeDetector.squeezed(bandWidth, intParam(params, "lookback"), param(params, "squeezePct"));
        boolean[] armed = previous(squeezed);
        boolean[] longEntry = and(armed, above(bars.close, upperBand));
        boolean[] shortEntry = and(armed, below(bars.close, lowerBand));
        return clean(hold(longEntry, below(bars.close, middleBand), shortEntry, above(bars.close, middleBand)));
    }
}

class Nr7Breakout extends AbstractStrategy {
    public String name() {
        return "nr7_breakout";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Narrowest-range-7 contraction, trade next-day range breakout.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 7.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int period = intParam(params, "n");
        int length = bars.size();
        double[] range = new double[length];
        for (int i = 0; i < length; i++) {
            range[i] = bars.high[i] - bars.low[i];
        }
        double[] narrowestRange = Indicators.rollingMin(range, period);
        boolean[] isNarrowRange = new boolean[length];
        double[] narrowRangeHigh = new double[length];
        double[] narrowRangeLow = new double[length];
        Arrays.fill(narrowRangeHigh, Double.NaN);
        Arrays.fill(narrowRangeLow, Double.NaN);
        double lastHigh = Double.NaN;
        double lastLow = Double.NaN;
        for (int i = 0; i < length; i++) {
            if (!Double.isNaN(narrowestRange[i]) && range[i] == narrowestRange[i]) {
                isNarrowRange[i] = true;
                lastHigh = bars.high[i];
                lastLow = bars.low[i];
            }
            narrowRangeHigh[i] = lastHigh;
            narrowRangeLow[i] = lastLow;
        }
        boolean[] armed = previous(isNarrowRange);
        boolean[] longEntry = and(armed, above(bars.high, previous(narrowRangeHigh)));
        boolean[] shortEntry = and(armed, below(bars.low, previous(narrowRangeLow)));
        double[] exitMa = Indicators.sma(bars.close, 5);
        return clean(hold(longEntry, below(bars.close, exitMa), shortEntry, above(bars.close, exitMa)));
    }
}

class CciBreakout extends AbstractStrategy {
    public String name() {
        return "cci_breakout";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "CCI breaking +/-100 initiates a held directional trade.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "level", 100.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] cci = Indicators.cci(bars, intParam(params, "n"));
        double level = param(params, "level");
        return clean(hold(above(cci, level), below(cci, 0), below(cci, -level), above(cci, 0)));
    }
}

class VortexBreakout extends AbstractStrategy {
    public String name() {
        return "vortex_breakout";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Vortex crossover confirmed by ADX trend strength.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 14.0, "adxMin", 20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[][] vortex = Indicators.vortex(bars, intParam(params, "n"));
        double[] plusVortex = vortex[0];
        double[] minusVortex = vortex[1];
        double[] adxLine = Indicators.adx(bars, intParam(params, "n"))[0];
        boolean[] strongTrend = above(adxLine, param(params, "adxMin"));
        boolean[] longEntry = and(strongTrend, above(plusVortex, minusVortex));
        boolean[] shortEntry = and(strongTrend, above(minusVortex, plusVortex));
        return clean(hold(longEntry, below(plusVortex, minusVortex), shortEntry, above(plusVortex, minusVortex)));
    }
}

class DonchianSqueeze extends AbstractStrategy {
    public String name() {
        return "donchian_squeeze";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Trade the breakout that follows a contraction in Donchian channel width.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "squeezePct", 0.25, "lookback", 120.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int period = intParam(params, "n");
        int length = bars.size();
        double[] highestHigh = Indicators.rollingMax(bars.high, period);
        double[] lowestLow = Indicators.rollingMin(bars.low, period);
        double[] channelWidth = new double[length];
        for (int i = 0; i < length; i++) {
            channelWidth[i] = (Double.isNaN(highestHigh[i]) || bars.close[i] == 0)
                    ? Double.NaN
                    : (highestHigh[i] - lowestLow[i]) / bars.close[i];
        }
        boolean[] squeezed =
                SqueezeDetector.squeezed(channelWidth, intParam(params, "lookback"), param(params, "squeezePct"));
        boolean[] armed = previous(squeezed);
        double[] midline = new double[length];
        for (int i = 0; i < length; i++) {
            midline[i] = (Double.isNaN(highestHigh[i]) || Double.isNaN(lowestLow[i]))
                    ? Double.NaN
                    : (highestHigh[i] + lowestLow[i]) / 2;
        }
        boolean[] longEntry = and(armed, above(bars.close, highestHigh));
        boolean[] shortEntry = and(armed, below(bars.close, lowestLow));
        return clean(hold(longEntry, below(bars.close, midline), shortEntry, above(bars.close, midline)));
    }
}

class VolatilityExpansionBreakout extends AbstractStrategy {
    public String name() {
        return "volatility_expansion_breakout";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Trade the direction of a sharp jump in realised volatility.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 10.0, "baseN", 60.0, "mult", 1.8, "exitMa", 10.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        double[] returns = new double[length];
        for (int i = 1; i < length; i++) {
            returns[i] = bars.close[i] / bars.close[i - 1] - 1;
        }
        double[] realizedVolatility = Indicators.stddev(returns, intParam(params, "n"));
        double[] baselineVolatility = Indicators.sma(realizedVolatility, intParam(params, "baseN"));
        double multiplier = param(params, "mult");
        boolean[] expanding = new boolean[length];
        for (int i = 0; i < length; i++) {
            expanding[i] = !Double.isNaN(realizedVolatility[i])
                    && !Double.isNaN(baselineVolatility[i])
                    && realizedVolatility[i] > baselineVolatility[i] * multiplier;
        }
        boolean[] longEntry = and(expanding, above(bars.close, previous(bars.close)));
        boolean[] shortEntry = and(expanding, below(bars.close, previous(bars.close)));
        double[] exitMa = Indicators.sma(bars.close, intParam(params, "exitMa"));
        return clean(hold(longEntry, below(bars.close, exitMa), shortEntry, above(bars.close, exitMa)));
    }
}

class PivotPointBreakout extends AbstractStrategy {
    public String name() {
        return "pivot_point_breakout";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Classic floor-trader pivot: trade a close beyond yesterday's R1/S1.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("exitMa", 10.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        double[] resistance1 = new double[length];
        double[] support1 = new double[length];
        for (int i = 1; i < length; i++) {
            double pivot = (bars.high[i - 1] + bars.low[i - 1] + bars.close[i - 1]) / 3;
            resistance1[i] = 2 * pivot - bars.low[i - 1];
            support1[i] = 2 * pivot - bars.high[i - 1];
        }
        double[] exitMa = Indicators.sma(bars.close, intParam(params, "exitMa"));
        boolean[] longEntry = above(bars.close, resistance1);
        boolean[] shortEntry = below(bars.close, support1);
        return clean(hold(longEntry, below(bars.close, exitMa), shortEntry, above(bars.close, exitMa)));
    }
}

class CamarillaBreakout extends AbstractStrategy {
    public String name() {
        return "camarilla_breakout";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Camarilla pivots: trade a close beyond the R3/S3 breakout levels.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("exitMa", 10.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        double[] resistance3 = new double[length];
        double[] support3 = new double[length];
        for (int i = 1; i < length; i++) {
            double range = bars.high[i - 1] - bars.low[i - 1];
            resistance3[i] = bars.close[i - 1] + range * 1.1 / 4;
            support3[i] = bars.close[i - 1] - range * 1.1 / 4;
        }
        double[] exitMa = Indicators.sma(bars.close, intParam(params, "exitMa"));
        boolean[] longEntry = above(bars.close, resistance3);
        boolean[] shortEntry = below(bars.close, support3);
        return clean(hold(longEntry, below(bars.close, exitMa), shortEntry, above(bars.close, exitMa)));
    }
}

class RangeExpansionBreakout extends AbstractStrategy {
    public String name() {
        return "range_expansion_breakout";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "A single bar's range far exceeding its ATR signals a breakout day.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("atrN", 14.0, "mult", 1.5, "exitMa", 10.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] atr = Indicators.atr(bars, intParam(params, "atrN"));
        double multiplier = param(params, "mult");
        int length = bars.size();
        boolean[] wideBar = new boolean[length];
        for (int i = 0; i < length; i++) {
            wideBar[i] = !Double.isNaN(atr[i]) && (bars.high[i] - bars.low[i]) > atr[i] * multiplier;
        }
        boolean[] longEntry = and(wideBar, above(bars.close, bars.open));
        boolean[] shortEntry = and(wideBar, below(bars.close, bars.open));
        double[] exitMa = Indicators.sma(bars.close, intParam(params, "exitMa"));
        return clean(hold(longEntry, below(bars.close, exitMa), shortEntry, above(bars.close, exitMa)));
    }
}

class ThreeBarBreakout extends AbstractStrategy {
    public String name() {
        return "three_bar_breakout";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Break of the prior 3 bars' high/low — a short-lookback breakout.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 3.0, "exitMa", 5.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int period = intParam(params, "n");
        double[] priorHigh = previous(Indicators.rollingMax(bars.high, period));
        double[] priorLow = previous(Indicators.rollingMin(bars.low, period));
        double[] exitMa = Indicators.sma(bars.close, intParam(params, "exitMa"));
        return clean(hold(
                above(bars.close, priorHigh),
                below(bars.close, exitMa),
                below(bars.close, priorLow),
                above(bars.close, exitMa)));
    }
}

class MacdZeroCross extends AbstractStrategy {
    public String name() {
        return "macd_zero_cross";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "MACD line crossing its own zero line (distinct from a signal-line cross).";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("fast", 12.0, "slow", 26.0, "signal", 9.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] macdLine = Indicators.macd(
                bars.close, intParam(params, "fast"), intParam(params, "slow"), intParam(params, "signal"))[0];
        return clean(regime(above(macdLine, 0), below(macdLine, 0)));
    }
}

class LinregChannelBreakout extends AbstractStrategy {
    public String name() {
        return "linreg_channel_breakout";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Breakout of a rolling linear-regression channel (fit +/- k*stddev).";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 30.0, "mult", 2.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int period = intParam(params, "n");
        int length = bars.size();
        double[] fitted = Indicators.linreg(bars.close, period)[0];
        double[] residual = new double[length];
        for (int i = 0; i < length; i++) {
            residual[i] = Double.isNaN(fitted[i]) ? Double.NaN : bars.close[i] - fitted[i];
        }
        double[] residualDeviation = Indicators.stddev(residual, period);
        double multiplier = param(params, "mult");
        double[] upperBand = new double[length];
        double[] lowerBand = new double[length];
        for (int i = 0; i < length; i++) {
            boolean warmingUp = Double.isNaN(fitted[i]) || Double.isNaN(residualDeviation[i]);
            upperBand[i] = warmingUp ? Double.NaN : fitted[i] + multiplier * residualDeviation[i];
            lowerBand[i] = warmingUp ? Double.NaN : fitted[i] - multiplier * residualDeviation[i];
        }
        return clean(hold(
                above(bars.close, upperBand),
                below(bars.close, fitted),
                below(bars.close, lowerBand),
                above(bars.close, fitted)));
    }
}
