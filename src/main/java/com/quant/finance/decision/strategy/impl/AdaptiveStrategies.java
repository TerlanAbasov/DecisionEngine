package com.quant.finance.decision.strategy.impl;

import com.quant.finance.decision.strategy.AbstractStrategy;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.Indicators;
import java.util.Map;

/**
 * Ten additional well-known systematic strategies on the existing indicator toolbox: adaptive moving averages, Ichimoku, Heikin-Ashi, ConnorsRSI,
 * QQE, the Alligator, the TTM squeeze, fractal breakouts, a choppiness-gated trend and RVI.
 */
final class AdaptiveShared {
    private AdaptiveShared() {}

    /** The bar-by-bar average of two series (NaN where either is NaN). */
    static double[] midpoint(double[] first, double[] second) {
        double[] result = new double[first.length];
        for (int i = 0; i < first.length; i++) {
            result[i] = (Double.isNaN(first[i]) || Double.isNaN(second[i])) ? Double.NaN : (first[i] + second[i]) / 2;
        }
        return result;
    }

    /** Shift a series forward by {@code bars} bars (result[i] = series[i - bars]); the leading slots are NaN. */
    static double[] shiftForward(double[] series, int bars) {
        double[] result = new double[series.length];
        for (int i = 0; i < series.length; i++) {
            result[i] = i >= bars ? series[i - bars] : Double.NaN;
        }
        return result;
    }

    /** Symmetric 4-tap weighted average (1,2,2,1)/6 — the RVI smoother. */
    static double[] symmetricWeightedAverage(double[] series) {
        double[] result = new double[series.length];
        for (int i = 0; i < series.length; i++) {
            result[i] = i < 3 ? Double.NaN : (series[i] + 2 * series[i - 1] + 2 * series[i - 2] + series[i - 3]) / 6.0;
        }
        return result;
    }

    /** Sum of the last {@code period} values (NaN counted as 0); NaN until a full window exists. */
    static double[] rollingSum(double[] series, int period) {
        double[] result = new double[series.length];
        double sum = 0;
        for (int i = 0; i < series.length; i++) {
            sum += Double.isNaN(series[i]) ? 0 : series[i];
            if (i >= period) {
                sum -= Double.isNaN(series[i - period]) ? 0 : series[i - period];
            }
            result[i] = i >= period - 1 ? sum : Double.NaN;
        }
        return result;
    }

    /** (high + low) / 2 for every bar. */
    static double[] medianPrice(BarSeries bars) {
        double[] result = new double[bars.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = (bars.high[i] + bars.low[i]) / 2;
        }
        return result;
    }
}

class IchimokuCloud extends AbstractStrategy {
    public String name() {
        return "ichimoku_cloud";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Ichimoku: Tenkan/Kijun cross confirmed by price on the same side of the cloud.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("tenkan", 9.0, "kijun", 26.0, "senkouB", 52.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int tenkanPeriod = intParam(params, "tenkan");
        int kijunPeriod = intParam(params, "kijun");
        int senkouBPeriod = intParam(params, "senkouB");
        double[] tenkan = AdaptiveShared.midpoint(
                Indicators.rollingMax(bars.high, tenkanPeriod), Indicators.rollingMin(bars.low, tenkanPeriod));
        double[] kijun = AdaptiveShared.midpoint(
                Indicators.rollingMax(bars.high, kijunPeriod), Indicators.rollingMin(bars.low, kijunPeriod));
        double[] spanA = AdaptiveShared.midpoint(tenkan, kijun);
        double[] spanB = AdaptiveShared.midpoint(
                Indicators.rollingMax(bars.high, senkouBPeriod), Indicators.rollingMin(bars.low, senkouBPeriod));
        int length = bars.size();
        boolean[] longCondition = new boolean[length];
        boolean[] shortCondition = new boolean[length];
        for (int i = 0; i < length; i++) {
            if (Double.isNaN(spanA[i]) || Double.isNaN(spanB[i]) || Double.isNaN(tenkan[i]) || Double.isNaN(kijun[i])) {
                continue;
            }
            double cloudTop = Math.max(spanA[i], spanB[i]);
            double cloudBottom = Math.min(spanA[i], spanB[i]);
            longCondition[i] = bars.close[i] > cloudTop && tenkan[i] > kijun[i];
            shortCondition[i] = bars.close[i] < cloudBottom && tenkan[i] < kijun[i];
        }
        return clean(regime(longCondition, shortCondition));
    }
}

class HeikinAshiTrend extends AbstractStrategy {
    public String name() {
        return "heikin_ashi_trend";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Ride runs of same-colour Heikin-Ashi candles.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("confirm", 2.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        int confirmBars = Math.max(1, intParam(params, "confirm"));
        double[] haClose = new double[length];
        double[] haOpen = new double[length];
        for (int i = 0; i < length; i++) {
            haClose[i] = (bars.open[i] + bars.high[i] + bars.low[i] + bars.close[i]) / 4.0;
            haOpen[i] = i == 0 ? (bars.open[i] + bars.close[i]) / 2.0 : (haOpen[i - 1] + haClose[i - 1]) / 2.0;
        }
        boolean[] longCondition = new boolean[length];
        boolean[] shortCondition = new boolean[length];
        for (int i = confirmBars - 1; i < length; i++) {
            boolean allGreen = true;
            boolean allRed = true;
            for (int j = 0; j < confirmBars; j++) {
                allGreen &= haClose[i - j] > haOpen[i - j];
                allRed &= haClose[i - j] < haOpen[i - j];
            }
            longCondition[i] = allGreen;
            shortCondition[i] = allRed;
        }
        return clean(regime(longCondition, shortCondition));
    }
}

class ConnorsRsi extends AbstractStrategy {
    public String name() {
        return "connors_rsi";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "ConnorsRSI (RSI + streak-RSI + %-rank): buy the oversold composite, exit on recovery.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("rsiLen", 3.0, "streakLen", 2.0, "rankLen", 100.0, "entry", 10.0, "exit", 70.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        int rankPeriod = intParam(params, "rankLen");
        double[] rsi = Indicators.rsi(bars.close, intParam(params, "rsiLen"));
        double[] streak = new double[length];
        for (int i = 1; i < length; i++) {
            if (bars.close[i] > bars.close[i - 1]) {
                streak[i] = Math.max(1, streak[i - 1] + 1);
            } else if (bars.close[i] < bars.close[i - 1]) {
                streak[i] = Math.min(-1, streak[i - 1] - 1);
            } else {
                streak[i] = 0;
            }
        }
        double[] streakRsi = Indicators.rsi(streak, intParam(params, "streakLen"));
        double[] dailyChange = Indicators.roc(bars.close, 1);
        double[] connorsRsi = new double[length];
        for (int i = 0; i < length; i++) {
            if (i < rankPeriod || Double.isNaN(rsi[i]) || Double.isNaN(streakRsi[i]) || Double.isNaN(dailyChange[i])) {
                connorsRsi[i] = Double.NaN;
                continue;
            }
            int smallerChanges = 0;
            for (int j = i - rankPeriod; j < i; j++) {
                if (!Double.isNaN(dailyChange[j]) && dailyChange[j] < dailyChange[i]) {
                    smallerChanges++;
                }
            }
            double percentRank = 100.0 * smallerChanges / rankPeriod;
            connorsRsi[i] = (rsi[i] + streakRsi[i] + percentRank) / 3.0;
        }
        boolean[] longEntry = below(connorsRsi, param(params, "entry"));
        boolean[] longExit = above(connorsRsi, param(params, "exit"));
        return clean(hold(longEntry, longExit, null, null));
    }
}

class KamaTrend extends AbstractStrategy {
    public String name() {
        return "kama_trend";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Kaufman Adaptive MA: fast in trends, flat in noise — trade price vs KAMA and its slope.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("er", 10.0, "fast", 2.0, "slow", 30.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        int efficiencyPeriod = Math.max(2, intParam(params, "er"));
        double fastConstant = 2.0 / (intParam(params, "fast") + 1);
        double slowConstant = 2.0 / (intParam(params, "slow") + 1);
        double[] kama = new double[length];
        for (int i = 0; i < length; i++) {
            if (i < efficiencyPeriod) {
                kama[i] = bars.close[i];
                continue;
            }
            double change = Math.abs(bars.close[i] - bars.close[i - efficiencyPeriod]);
            double volatility = 0;
            for (int j = i - efficiencyPeriod + 1; j <= i; j++) {
                volatility += Math.abs(bars.close[j] - bars.close[j - 1]);
            }
            double efficiencyRatio = volatility > 0 ? change / volatility : 0;
            double smoothing = Math.pow(efficiencyRatio * (fastConstant - slowConstant) + slowConstant, 2);
            kama[i] = kama[i - 1] + smoothing * (bars.close[i] - kama[i - 1]);
        }
        boolean[] longCondition = new boolean[length];
        boolean[] shortCondition = new boolean[length];
        for (int i = efficiencyPeriod + 1; i < length; i++) {
            double slope = kama[i] - kama[i - 1];
            longCondition[i] = bars.close[i] > kama[i] && slope > 0;
            shortCondition[i] = bars.close[i] < kama[i] && slope < 0;
        }
        return clean(regime(longCondition, shortCondition));
    }
}

class QqeSignal extends AbstractStrategy {
    public String name() {
        return "qqe_signal";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "QQE: a smoothed RSI ridden with an ATR-of-RSI trailing stop.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("rsiLen", 14.0, "smooth", 5.0, "factor", 4.236);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        double[] smoothedRsi =
                Indicators.ema(Indicators.rsi(bars.close, intParam(params, "rsiLen")), intParam(params, "smooth"));
        double[] rsiChange = new double[length];
        for (int i = 1; i < length; i++) {
            rsiChange[i] = Double.isNaN(smoothedRsi[i]) || Double.isNaN(smoothedRsi[i - 1])
                    ? 0
                    : Math.abs(smoothedRsi[i] - smoothedRsi[i - 1]);
        }
        double[] band = Indicators.ema(rsiChange, intParam(params, "rsiLen") * 2 - 1);
        double factor = param(params, "factor");
        boolean[] longCondition = new boolean[length];
        boolean[] shortCondition = new boolean[length];
        double trailingStop = Double.NaN;
        for (int i = 0; i < length; i++) {
            if (Double.isNaN(smoothedRsi[i]) || Double.isNaN(band[i])) {
                continue;
            }
            double stopDistance = band[i] * factor;
            if (Double.isNaN(trailingStop)) {
                trailingStop = smoothedRsi[i] - stopDistance;
            }
            trailingStop = smoothedRsi[i] > trailingStop
                    ? Math.max(trailingStop, smoothedRsi[i] - stopDistance)
                    : Math.min(trailingStop, smoothedRsi[i] + stopDistance);
            longCondition[i] = smoothedRsi[i] > trailingStop;
            shortCondition[i] = smoothedRsi[i] < trailingStop;
        }
        return clean(regime(longCondition, shortCondition));
    }
}

class WilliamsAlligator extends AbstractStrategy {
    public String name() {
        return "williams_alligator";
    }

    public String category() {
        return "trend";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Bill Williams Alligator: trade when the displaced SMMA lines (lips/teeth/jaw) fan out.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("jaw", 13.0, "teeth", 8.0, "lips", 5.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] medianPrice = AdaptiveShared.medianPrice(bars);
        double[] jaw = AdaptiveShared.shiftForward(Indicators.wilder(medianPrice, intParam(params, "jaw")), 8);
        double[] teeth = AdaptiveShared.shiftForward(Indicators.wilder(medianPrice, intParam(params, "teeth")), 5);
        double[] lips = AdaptiveShared.shiftForward(Indicators.wilder(medianPrice, intParam(params, "lips")), 3);
        boolean[] fannedUp = and(above(lips, teeth), above(teeth, jaw));
        boolean[] fannedDown = and(below(lips, teeth), below(teeth, jaw));
        return clean(regime(fannedUp, fannedDown));
    }
}

class TtmSqueeze extends AbstractStrategy {
    public String name() {
        return "ttm_squeeze";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "TTM squeeze: Bollinger inside Keltner = coiled; fire on release in the momentum direction.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "bbK", 2.0, "kcMult", 1.5);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        int period = intParam(params, "n");
        double[][] bollinger = Indicators.bollinger(bars.close, period, param(params, "bbK"));
        double[][] keltner = Indicators.keltner(bars, period, param(params, "kcMult"));
        double[] slope = Indicators.linreg(bars.close, period)[1];
        boolean[] longEntry = new boolean[length];
        boolean[] shortEntry = new boolean[length];
        boolean[] longExit = new boolean[length];
        boolean[] shortExit = new boolean[length];
        boolean wasSqueezed = false;
        for (int i = 0; i < length; i++) {
            if (Double.isNaN(bollinger[0][i]) || Double.isNaN(keltner[0][i]) || Double.isNaN(slope[i])) {
                continue;
            }
            boolean bollingerInsideKeltner = bollinger[0][i] > keltner[0][i] && bollinger[2][i] < keltner[2][i];
            boolean released = wasSqueezed && !bollingerInsideKeltner;
            wasSqueezed = bollingerInsideKeltner;
            if (released && slope[i] > 0) {
                longEntry[i] = true;
            }
            if (released && slope[i] < 0) {
                shortEntry[i] = true;
            }
            longExit[i] = slope[i] < 0; // exit long once momentum rolls over
            shortExit[i] = slope[i] > 0;
        }
        return clean(hold(longEntry, longExit, shortEntry, shortExit));
    }
}

class FractalBreakout extends AbstractStrategy {
    public String name() {
        return "fractal_breakout";
    }

    public String category() {
        return "breakout";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Trade breaks of the most recent Williams 5-bar fractal high / low.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of();
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        double[] position = new double[length];
        double lastFractalHigh = Double.NaN;
        double lastFractalLow = Double.NaN;
        int state = 0;
        for (int i = 0; i < length; i++) {
            int center = i - 2; // fractal confirmed 2 bars back
            if (center >= 2 && center + 2 < length) {
                if (bars.high[center] > bars.high[center - 1]
                        && bars.high[center] > bars.high[center - 2]
                        && bars.high[center] > bars.high[center + 1]
                        && bars.high[center] > bars.high[center + 2]) {
                    lastFractalHigh = bars.high[center];
                }
                if (bars.low[center] < bars.low[center - 1]
                        && bars.low[center] < bars.low[center - 2]
                        && bars.low[center] < bars.low[center + 1]
                        && bars.low[center] < bars.low[center + 2]) {
                    lastFractalLow = bars.low[center];
                }
            }
            if (!Double.isNaN(lastFractalHigh) && bars.close[i] > lastFractalHigh) {
                state = 1;
            } else if (!Double.isNaN(lastFractalLow) && bars.close[i] < lastFractalLow) {
                state = -1;
            }
            position[i] = state;
        }
        return clean(position);
    }
}

class ChoppinessTrend extends AbstractStrategy {
    public String name() {
        return "choppiness_trend";
    }

    public String category() {
        return "hybrid";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Only take the EMA-cross trend when the Choppiness Index says the market is trending.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 14.0, "fast", 20.0, "slow", 50.0, "chopMax", 38.2);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        int period = intParam(params, "n");
        double[] trueRangeSum = AdaptiveShared.rollingSum(Indicators.trueRange(bars), period);
        double[] highestHigh = Indicators.rollingMax(bars.high, period);
        double[] lowestLow = Indicators.rollingMin(bars.low, period);
        double logPeriod = Math.log10(period);
        double[] fastEma = Indicators.ema(bars.close, intParam(params, "fast"));
        double[] slowEma = Indicators.ema(bars.close, intParam(params, "slow"));
        double chopMax = param(params, "chopMax");
        boolean[] longCondition = new boolean[length];
        boolean[] shortCondition = new boolean[length];
        for (int i = 0; i < length; i++) {
            if (Double.isNaN(trueRangeSum[i])
                    || Double.isNaN(highestHigh[i])
                    || Double.isNaN(lowestLow[i])
                    || Double.isNaN(fastEma[i])
                    || Double.isNaN(slowEma[i])) {
                continue;
            }
            double range = highestHigh[i] - lowestLow[i];
            if (range <= 0) {
                continue;
            }
            double choppiness = 100 * Math.log10(trueRangeSum[i] / range) / logPeriod;
            if (choppiness >= chopMax) {
                continue; // too choppy — stay flat
            }
            longCondition[i] = fastEma[i] > slowEma[i];
            shortCondition[i] = fastEma[i] < slowEma[i];
        }
        return clean(regime(longCondition, shortCondition));
    }
}

class RviSignal extends AbstractStrategy {
    public String name() {
        return "rvi_signal";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Relative Vigor Index: close-vs-open strength normalised by range, traded against its signal line.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 10.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        int period = intParam(params, "n");
        double[] closeMinusOpen = new double[length];
        double[] highMinusLow = new double[length];
        for (int i = 0; i < length; i++) {
            closeMinusOpen[i] = bars.close[i] - bars.open[i];
            highMinusLow[i] = bars.high[i] - bars.low[i];
        }
        double[] numerator = AdaptiveShared.rollingSum(AdaptiveShared.symmetricWeightedAverage(closeMinusOpen), period);
        double[] denominator = AdaptiveShared.rollingSum(AdaptiveShared.symmetricWeightedAverage(highMinusLow), period);
        double[] rvi = new double[length];
        for (int i = 0; i < length; i++) {
            rvi[i] = (Double.isNaN(numerator[i]) || Double.isNaN(denominator[i]) || denominator[i] == 0)
                    ? Double.NaN
                    : numerator[i] / denominator[i];
        }
        double[] signalLine = AdaptiveShared.symmetricWeightedAverage(rvi);
        return clean(regime(above(rvi, signalLine), below(rvi, signalLine)));
    }
}
