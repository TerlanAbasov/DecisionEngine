package com.quant.finance.decision.strategy.impl;

import com.quant.finance.decision.strategy.AbstractStrategy;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.Indicators;
import java.util.Map;

class Rsi2 extends AbstractStrategy {
    public String name() {
        return "rsi2";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "Connors RSI(2): buy pullbacks above the 200-SMA, exit on strength.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("trendMa", 200.0, "rsiN", 2.0, "entry", 10.0, "exitMa", 5.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        boolean[] upTrend = above(bars.close, Indicators.sma(bars.close, intParam(params, "trendMa")));
        double[] rsi = Indicators.rsi(bars.close, intParam(params, "rsiN"));
        boolean[] longEntry = and(upTrend, below(rsi, param(params, "entry")));
        boolean[] longExit = above(bars.close, Indicators.sma(bars.close, intParam(params, "exitMa")));
        return clean(hold(longEntry, longExit, null, null));
    }
}

class Rsi14 extends AbstractStrategy {
    public String name() {
        return "rsi14";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Classic RSI(14) oversold/overbought reversion.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 14.0, "low", 30.0, "high", 70.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] rsi = Indicators.rsi(bars.close, intParam(params, "n"));
        return clean(hold(
                below(rsi, param(params, "low")), above(rsi, 50), above(rsi, param(params, "high")), below(rsi, 50)));
    }
}

class BollingerReversion extends AbstractStrategy {
    public String name() {
        return "bollinger_reversion";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Fade touches of the outer Bollinger Bands back to the mean.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "k", 2.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[][] bollinger = Indicators.bollinger(bars.close, intParam(params, "n"), param(params, "k"));
        double[] lowerBand = bollinger[0];
        double[] middleBand = bollinger[1];
        double[] upperBand = bollinger[2];
        return clean(hold(
                below(bars.close, lowerBand),
                above(bars.close, middleBand),
                above(bars.close, upperBand),
                below(bars.close, middleBand)));
    }
}

class ZScoreReversion extends AbstractStrategy {
    public String name() {
        return "zscore_reversion";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Trade the rolling z-score of price back toward zero.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "entry", 2.0, "exit", 0.5);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] zScore = Indicators.zscore(bars.close, intParam(params, "n"));
        double entry = param(params, "entry");
        double exit = param(params, "exit");
        return clean(hold(below(zScore, -entry), above(zScore, -exit), above(zScore, entry), below(zScore, exit)));
    }
}

class WilliamsRStrat extends AbstractStrategy {
    public String name() {
        return "williams_r";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Williams %R extremes reversion.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 14.0, "low", -80.0, "high", -20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] williamsR = Indicators.williamsR(bars, intParam(params, "n"));
        return clean(hold(
                below(williamsR, param(params, "low")),
                above(williamsR, -50),
                above(williamsR, param(params, "high")),
                below(williamsR, -50)));
    }
}

class StochReversion extends AbstractStrategy {
    public String name() {
        return "stochastic";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Stochastic %K/%D crossovers from oversold/overbought zones.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 14.0, "d", 3.0, "low", 20.0, "high", 80.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[][] stochastic = Indicators.stochastic(bars, intParam(params, "n"), intParam(params, "d"));
        double[] percentK = stochastic[0];
        double[] percentD = stochastic[1];
        boolean[] longEntry = and(below(percentK, param(params, "low")), above(percentK, percentD));
        boolean[] shortEntry = and(above(percentK, param(params, "high")), below(percentK, percentD));
        return clean(hold(longEntry, above(percentK, 50), shortEntry, below(percentK, 50)));
    }
}

class CciReversion extends AbstractStrategy {
    public String name() {
        return "cci_reversion";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "CCI extremes fade back toward zero.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "low", -100.0, "high", 100.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] cci = Indicators.cci(bars, intParam(params, "n"));
        return clean(hold(
                below(cci, param(params, "low")), above(cci, 0), above(cci, param(params, "high")), below(cci, 0)));
    }
}

class MfiReversion extends AbstractStrategy {
    public String name() {
        return "mfi_reversion";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Money Flow Index oversold/overbought reversion.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 14.0, "low", 20.0, "high", 80.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] mfi = Indicators.mfi(bars, intParam(params, "n"));
        return clean(hold(
                below(mfi, param(params, "low")), above(mfi, 50), above(mfi, param(params, "high")), below(mfi, 50)));
    }
}

class CmoReversion extends AbstractStrategy {
    public String name() {
        return "cmo_reversion";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Chande Momentum Oscillator extremes fade.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 14.0, "low", -50.0, "high", 50.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] cmo = Indicators.cmo(bars.close, intParam(params, "n"));
        return clean(hold(
                below(cmo, param(params, "low")), above(cmo, 0), above(cmo, param(params, "high")), below(cmo, 0)));
    }
}

class BollingerPctB extends AbstractStrategy {
    public String name() {
        return "bollinger_pctb";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Bollinger %b (position within the bands) extremes fade.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "k", 2.0, "low", 0.05, "high", 0.95);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[][] bollinger = Indicators.bollinger(bars.close, intParam(params, "n"), param(params, "k"));
        double[] lowerBand = bollinger[0];
        double[] upperBand = bollinger[2];
        int length = bars.size();
        double[] percentB = new double[length];
        for (int i = 0; i < length; i++) {
            double range = upperBand[i] - lowerBand[i];
            percentB[i] =
                    (Double.isNaN(upperBand[i]) || range == 0) ? Double.NaN : (bars.close[i] - lowerBand[i]) / range;
        }
        return clean(hold(
                below(percentB, param(params, "low")),
                above(percentB, 0.5),
                above(percentB, param(params, "high")),
                below(percentB, 0.5)));
    }
}

class Rsi21 extends AbstractStrategy {
    public String name() {
        return "rsi21";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Slower RSI(21) oversold/overbought reversion, fewer whipsaws than RSI(14).";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 21.0, "low", 35.0, "high", 65.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] rsi = Indicators.rsi(bars.close, intParam(params, "n"));
        return clean(hold(
                below(rsi, param(params, "low")), above(rsi, 50), above(rsi, param(params, "high")), below(rsi, 50)));
    }
}

class Rsi2Extreme extends AbstractStrategy {
    public String name() {
        return "rsi2_extreme";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "RSI(2) at extreme readings, traded both long and short (no trend filter).";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 2.0, "low", 5.0, "high", 95.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] rsi = Indicators.rsi(bars.close, intParam(params, "n"));
        return clean(hold(
                below(rsi, param(params, "low")), above(rsi, 50), above(rsi, param(params, "high")), below(rsi, 50)));
    }
}

class DpoReversion extends AbstractStrategy {
    public String name() {
        return "dpo_reversion";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Detrended Price Oscillator: fade stretches away from the cycle mean.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "band", 0.05);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] dpo = Indicators.dpo(bars.close, intParam(params, "n"));
        int length = bars.size();
        double[] dpoPercent = new double[length];
        for (int i = 0; i < length; i++) {
            dpoPercent[i] = (Double.isNaN(dpo[i]) || bars.close[i] == 0) ? Double.NaN : dpo[i] / bars.close[i];
        }
        double band = param(params, "band");
        return clean(
                hold(below(dpoPercent, -band), above(dpoPercent, 0), above(dpoPercent, band), below(dpoPercent, 0)));
    }
}

class KeltnerReversion extends AbstractStrategy {
    public String name() {
        return "keltner_reversion";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Fade touches of the Keltner channel bands back toward the midline.";
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
                below(bars.close, lowerBand),
                above(bars.close, middleBand),
                above(bars.close, upperBand),
                below(bars.close, middleBand)));
    }
}

class VwapBandReversion extends AbstractStrategy {
    public String name() {
        return "vwap_band_reversion";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Symmetric fade of both VWAP bands, long and short.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "band", 0.03);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] vwap = Indicators.vwap(bars, intParam(params, "n"));
        double band = param(params, "band");
        int length = bars.size();
        double[] lowerBand = new double[length];
        double[] upperBand = new double[length];
        for (int i = 0; i < length; i++) {
            lowerBand[i] = Double.isNaN(vwap[i]) ? Double.NaN : vwap[i] * (1 - band);
            upperBand[i] = Double.isNaN(vwap[i]) ? Double.NaN : vwap[i] * (1 + band);
        }
        return clean(hold(
                below(bars.close, lowerBand),
                above(bars.close, vwap),
                above(bars.close, upperBand),
                below(bars.close, vwap)));
    }
}

class AtrBandReversion extends AbstractStrategy {
    public String name() {
        return "atr_band_reversion";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Fade a k*ATR excursion from the SMA back toward the mean.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "mult", 2.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] middleBand = Indicators.sma(bars.close, intParam(params, "n"));
        double[] atr = Indicators.atr(bars, intParam(params, "n"));
        double multiplier = param(params, "mult");
        int length = bars.size();
        double[] upperBand = new double[length];
        double[] lowerBand = new double[length];
        for (int i = 0; i < length; i++) {
            boolean warmingUp = Double.isNaN(middleBand[i]) || Double.isNaN(atr[i]);
            upperBand[i] = warmingUp ? Double.NaN : middleBand[i] + multiplier * atr[i];
            lowerBand[i] = warmingUp ? Double.NaN : middleBand[i] - multiplier * atr[i];
        }
        return clean(hold(
                below(bars.close, lowerBand),
                above(bars.close, middleBand),
                above(bars.close, upperBand),
                below(bars.close, middleBand)));
    }
}

class UltimateOscillatorReversion extends AbstractStrategy {
    public String name() {
        return "ultimate_oscillator";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Williams' Ultimate Oscillator extremes reversion.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n1", 7.0, "n2", 14.0, "n3", 28.0, "low", 30.0, "high", 70.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] oscillator = Indicators.ultimateOscillator(
                bars, intParam(params, "n1"), intParam(params, "n2"), intParam(params, "n3"));
        return clean(hold(
                below(oscillator, param(params, "low")),
                above(oscillator, 50),
                above(oscillator, param(params, "high")),
                below(oscillator, 50)));
    }
}

class StochRsi extends AbstractStrategy {
    public String name() {
        return "stoch_rsi";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Stochastic oscillator applied to RSI (StochRSI) extremes.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("rsiN", 14.0, "stochN", 14.0, "low", 20.0, "high", 80.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] rsi = Indicators.rsi(bars.close, intParam(params, "rsiN"));
        int stochPeriod = intParam(params, "stochN");
        int length = bars.size();
        double[] highestRsi = Indicators.rollingMax(rsi, stochPeriod);
        double[] lowestRsi = Indicators.rollingMin(rsi, stochPeriod);
        double[] stochRsi = new double[length];
        for (int i = 0; i < length; i++) {
            double range = highestRsi[i] - lowestRsi[i];
            stochRsi[i] =
                    (Double.isNaN(highestRsi[i]) || range == 0) ? Double.NaN : 100 * (rsi[i] - lowestRsi[i]) / range;
        }
        return clean(hold(
                below(stochRsi, param(params, "low")),
                above(stochRsi, 50),
                above(stochRsi, param(params, "high")),
                below(stochRsi, 50)));
    }
}

class WaveTrend extends AbstractStrategy {
    public String name() {
        return "wave_trend";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "LazyBear's WaveTrend oscillator: WT1/WT2 signal-line cross gated by overbought/oversold zones.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("channelLen", 10.0, "avgLen", 21.0, "obLevel", 60.0, "osLevel", -60.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[][] waveTrend = Indicators.waveTrend(bars, intParam(params, "channelLen"), intParam(params, "avgLen"));
        double[] wt1 = waveTrend[0];
        double[] wt2 = waveTrend[1];
        double overbought = param(params, "obLevel");
        double oversold = param(params, "osLevel");
        return clean(hold(
                and(above(wt1, wt2), below(wt2, oversold)), // long: bullish cross while oversold
                above(wt1, overbought), // exit long: reached overbought
                and(below(wt1, wt2), above(wt2, overbought)), // short: bearish cross while overbought
                below(wt1, oversold))); // exit short: reached oversold
    }
}

class SupportResistanceBounce extends AbstractStrategy {
    public String name() {
        return "support_resistance_bounce";
    }

    public String category() {
        return "mean_reversion";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Fade a rolling swing-high resistance / swing-low support: buy the bounce off "
                + "support, sell the decline off resistance, exit at the midpoint.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 20.0, "bufferPct", 15.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int period = intParam(params, "n");
        int length = bars.size();
        double bufferFraction = param(params, "bufferPct") / 100.0;
        double[] resistance = Indicators.rollingMax(bars.high, period);
        double[] support = Indicators.rollingMin(bars.low, period);
        double[] midpoint = new double[length];
        double[] supportZone = new double[length];
        double[] resistanceZone = new double[length];
        for (int i = 0; i < length; i++) {
            if (Double.isNaN(resistance[i]) || Double.isNaN(support[i])) {
                midpoint[i] = Double.NaN;
                supportZone[i] = Double.NaN;
                resistanceZone[i] = Double.NaN;
                continue;
            }
            double range = resistance[i] - support[i];
            midpoint[i] = (resistance[i] + support[i]) / 2.0;
            supportZone[i] = support[i] + bufferFraction * range; // "at/near support"
            resistanceZone[i] = resistance[i] - bufferFraction * range; // "at/near resistance"
        }
        return clean(hold(
                below(bars.close, supportZone), // buy: price back near support
                above(bars.close, midpoint), // exit long: reverted to the midpoint
                above(bars.close, resistanceZone), // sell: price back near resistance
                below(bars.close, midpoint))); // exit short: reverted to the midpoint
    }
}
