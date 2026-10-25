package com.quant.finance.decision.strategy.impl;

import com.quant.finance.decision.strategy.AbstractStrategy;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.Indicators;
import java.util.Map;

class RocMomentum extends AbstractStrategy {
    public String name() {
        return "roc_momentum";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Rate-of-change momentum: ride positive/negative n-day returns.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 90.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] roc = Indicators.roc(bars.close, intParam(params, "n"));
        return clean(regime(above(roc, 0), below(roc, 0)));
    }
}

class High52wBreakout extends AbstractStrategy {
    public String name() {
        return "high_52w_breakout";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "Buy new 52-week highs; exit on a 6-month low (Darvas-style).";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("hi", 252.0, "exitLo", 126.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        boolean[] longEntry = above(bars.close, previous(Indicators.rollingMax(bars.close, intParam(params, "hi"))));
        boolean[] longExit = below(bars.close, previous(Indicators.rollingMin(bars.close, intParam(params, "exitLo"))));
        return clean(hold(longEntry, longExit, null, null));
    }
}

class DualMomentum extends AbstractStrategy {
    public String name() {
        return "dual_momentum";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "Absolute momentum: long only when 12m and 3m returns agree.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("longN", 252.0, "shortN", 63.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        boolean[] bothPositive = and(
                above(Indicators.roc(bars.close, intParam(params, "longN")), 0),
                above(Indicators.roc(bars.close, intParam(params, "shortN")), 0));
        return clean(regime(bothPositive, null));
    }
}

class VolScaledMomentum extends AbstractStrategy {
    public String name() {
        return "vol_scaled_momentum";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Momentum sign sized inversely to realised volatility (risk-parity flavour).";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 90.0, "volN", 20.0, "targetVol", 0.15, "cap", 1.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] roc = Indicators.roc(bars.close, intParam(params, "n"));
        double[] returns = new double[bars.size()];
        for (int i = 1; i < bars.size(); i++) {
            returns[i] = bars.close[i] / bars.close[i - 1] - 1;
        }
        double[] realizedVolatility = Indicators.stddev(returns, intParam(params, "volN"));
        double[] signal = new double[bars.size()];
        double targetVolatility = param(params, "targetVol");
        double maxScale = param(params, "cap");
        for (int i = 0; i < bars.size(); i++) {
            if (Double.isNaN(roc[i]) || Double.isNaN(realizedVolatility[i]) || realizedVolatility[i] == 0) {
                continue;
            }
            double annualizedVolatility = realizedVolatility[i] * Math.sqrt(252);
            double scale = Math.min(maxScale, targetVolatility / annualizedVolatility);
            signal[i] = Math.signum(roc[i]) * scale;
        }
        return clean(signal);
    }
}

class RsiMomentum extends AbstractStrategy {
    public String name() {
        return "rsi_momentum";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "RSI as a trend gauge: long above 50, short below.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 14.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] rsi = Indicators.rsi(bars.close, intParam(params, "n"));
        return clean(regime(above(rsi, 50), below(rsi, 50)));
    }
}

class RsiMaCross extends AbstractStrategy {
    public String name() {
        return "rsi_ma_cross";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "RSI crossing its own moving average (TradingView's RSI-based MA), with the 70/50/30 bands as guards.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("rsiN", 14.0, "maN", 14.0, "upper", 70.0, "lower", 30.0, "midTol", 20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] rsi = Indicators.rsi(bars.close, intParam(params, "rsiN"));
        int maPeriod = intParam(params, "maN");
        // the MA is of the RSI, not of price; RSI's NaN warm-up would poison a plain sma
        double[] rsiMa = Indicators.fromFirstValid(rsi, series -> Indicators.sma(series, maPeriod));
        boolean[] crossUp = and(above(rsi, rsiMa), previous(below(rsi, rsiMa)));
        boolean[] crossDown = and(below(rsi, rsiMa), previous(above(rsi, rsiMa)));
        double midlineTolerance = param(params, "midTol");
        // Entries are the cross itself, but never a chase: no new long once RSI is already overbought, no new short
        // once it is oversold, and the MA must sit within `midTol` of the 50 midline on the trade's own side —
        // which keeps longs out of a washout and shorts out of a melt-up. midTol >= 50 turns that last guard off.
        boolean[] longEntry =
                and(and(crossUp, below(rsi, param(params, "upper"))), above(rsiMa, 50 - midlineTolerance));
        boolean[] shortEntry =
                and(and(crossDown, above(rsi, param(params, "lower"))), below(rsiMa, 50 + midlineTolerance));
        // exits are the bare opposite cross, so a cross that fails the entry guard still closes what is open
        return clean(hold(longEntry, crossDown, shortEntry, crossUp));
    }
}

class MultiHorizonMomentum extends AbstractStrategy {
    public String name() {
        return "multi_horizon_momentum";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Average sign of 1/3/6-month returns — momentum that agrees across horizons.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n1", 21.0, "n2", 63.0, "n3", 126.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] roc1 = Indicators.roc(bars.close, intParam(params, "n1"));
        double[] roc2 = Indicators.roc(bars.close, intParam(params, "n2"));
        double[] roc3 = Indicators.roc(bars.close, intParam(params, "n3"));
        int length = bars.size();
        double[] averageRoc = new double[length];
        for (int i = 0; i < length; i++) {
            if (Double.isNaN(roc1[i]) || Double.isNaN(roc2[i]) || Double.isNaN(roc3[i])) {
                averageRoc[i] = Double.NaN;
                continue;
            }
            averageRoc[i] = (roc1[i] + roc2[i] + roc3[i]) / 3;
        }
        return clean(regime(above(averageRoc, 0), below(averageRoc, 0)));
    }
}

class MacdHistogramSlope extends AbstractStrategy {
    public String name() {
        return "macd_histogram_slope";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "MACD histogram rising/falling — momentum acceleration, not just level.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("fast", 12.0, "slow", 26.0, "signal", 9.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] histogram = Indicators.macd(
                bars.close, intParam(params, "fast"), intParam(params, "slow"), intParam(params, "signal"))[2];
        double[] previousHistogram = previous(histogram);
        return clean(regime(above(histogram, previousHistogram), below(histogram, previousHistogram)));
    }
}

class ForceIndexMomentum extends AbstractStrategy {
    public String name() {
        return "force_index";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Elder's Force Index (EMA-smoothed price*volume thrust) sign.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 13.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] forceIndex = Indicators.forceIndex(bars, intParam(params, "n"));
        return clean(regime(above(forceIndex, 0), below(forceIndex, 0)));
    }
}

class CciMomentum extends AbstractStrategy {
    public String name() {
        return "cci_momentum";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "CCI as a trend gauge: long above zero, short below.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] cci = Indicators.cci(bars, intParam(params, "n"));
        return clean(regime(above(cci, 0), below(cci, 0)));
    }
}

class AroonOscillatorMomentum extends AbstractStrategy {
    public String name() {
        return "aroon_oscillator";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Aroon Oscillator (up - down) sign.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 25.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[][] aroon = Indicators.aroon(bars, intParam(params, "n"));
        double[] aroonUp = aroon[0];
        double[] aroonDown = aroon[1];
        int length = bars.size();
        double[] oscillator = new double[length];
        for (int i = 0; i < length; i++) {
            oscillator[i] =
                    (Double.isNaN(aroonUp[i]) || Double.isNaN(aroonDown[i])) ? Double.NaN : aroonUp[i] - aroonDown[i];
        }
        return clean(regime(above(oscillator, 0), below(oscillator, 0)));
    }
}

class RocAcceleration extends AbstractStrategy {
    public String name() {
        return "roc_acceleration";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Change in the rate-of-change itself — momentum of momentum.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] roc = Indicators.roc(bars.close, intParam(params, "n"));
        double[] previousRoc = previous(roc);
        return clean(regime(above(roc, previousRoc), below(roc, previousRoc)));
    }
}

class HullMomentum extends AbstractStrategy {
    public String name() {
        return "hull_momentum";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Slope of the Hull Moving Average — trades acceleration, not level.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 21.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] hullMa = Indicators.hullMa(bars.close, intParam(params, "n"));
        double[] previousHullMa = previous(hullMa);
        return clean(regime(above(hullMa, previousHullMa), below(hullMa, previousHullMa)));
    }
}

class TrixMomentum extends AbstractStrategy {
    public String name() {
        return "trix_momentum";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "TRIX versus its own signal-line average (MACD-of-TRIX).";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 15.0, "signal", 9.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] trix = Indicators.trix(bars.close, intParam(params, "n"));
        double[] trixWithoutNaN = trix.clone();
        for (int i = 0; i < trixWithoutNaN.length; i++) {
            if (Double.isNaN(trixWithoutNaN[i])) {
                trixWithoutNaN[i] = 0;
            }
        }
        double[] signalLine = Indicators.ema(trixWithoutNaN, intParam(params, "signal"));
        return clean(regime(above(trix, signalLine), below(trix, signalLine)));
    }
}

class StreakMomentum extends AbstractStrategy {
    public String name() {
        return "streak_momentum";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Consecutive up/down closing streak, capped, as a momentum signal.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("cap", 5.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        double maxStreak = param(params, "cap");
        double[] signal = new double[length];
        int streak = 0;
        for (int i = 1; i < length; i++) {
            if (bars.close[i] > bars.close[i - 1]) {
                streak = streak >= 0 ? streak + 1 : 1;
            } else if (bars.close[i] < bars.close[i - 1]) {
                streak = streak <= 0 ? streak - 1 : -1;
            } else {
                streak = 0;
            }
            signal[i] = Math.max(-maxStreak, Math.min(maxStreak, streak)) / maxStreak;
        }
        return clean(signal);
    }
}

class MaStackMomentum extends AbstractStrategy {
    public String name() {
        return "ma_stack_momentum";
    }

    public String category() {
        return "momentum";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Position scaled by how many of 4 SMAs (20/50/100/200) price sits above.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n1", 20.0, "n2", 50.0, "n3", 100.0, "n4", 200.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] ma1 = Indicators.sma(bars.close, intParam(params, "n1"));
        double[] ma2 = Indicators.sma(bars.close, intParam(params, "n2"));
        double[] ma3 = Indicators.sma(bars.close, intParam(params, "n3"));
        double[] ma4 = Indicators.sma(bars.close, intParam(params, "n4"));
        int length = bars.size();
        double[] signal = new double[length];
        for (int i = 0; i < length; i++) {
            if (Double.isNaN(ma4[i])) {
                signal[i] = Double.NaN;
                continue;
            }
            int movingAveragesBelowPrice = (bars.close[i] > ma1[i] ? 1 : 0)
                    + (bars.close[i] > ma2[i] ? 1 : 0)
                    + (bars.close[i] > ma3[i] ? 1 : 0)
                    + (bars.close[i] > ma4[i] ? 1 : 0);
            signal[i] = (movingAveragesBelowPrice / 4.0) * 2 - 1;
        }
        return clean(signal);
    }
}
