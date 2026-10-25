package com.quant.finance.decision.strategy;

import java.util.Arrays;
import java.util.function.UnaryOperator;

/**
 * Vectorised technical indicators over double[]. Every value at index t uses only
 * data at indices &lt;= t (no look-ahead). NaN is used for the warm-up window.
 */
public final class Indicators {
    private Indicators() {}

    private static double[] filledWithNaN(int length) {
        double[] result = new double[length];
        Arrays.fill(result, Double.NaN);
        return result;
    }

    public static double[] sma(double[] values, int period) {
        double[] out = filledWithNaN(values.length);
        double sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            if (i >= period) {
                sum -= values[i - period];
            }
            if (i >= period - 1) {
                out[i] = sum / period;
            }
        }
        return out;
    }

    public static double[] ema(double[] values, int period) {
        double[] out = filledWithNaN(values.length);
        double alpha = 2.0 / (period + 1);
        double previous = 0;
        boolean seeded = false;
        double seedSum = 0;
        for (int i = 0; i < values.length; i++) {
            if (!seeded) {
                seedSum += values[i];
                if (i == period - 1) {
                    previous = seedSum / period;
                    out[i] = previous;
                    seeded = true;
                }
            } else {
                previous = values[i] * alpha + previous * (1 - alpha);
                out[i] = previous;
            }
        }
        return out;
    }

    /** Wilder smoothing (alpha = 1/period). */
    public static double[] wilder(double[] values, int period) {
        double[] out = filledWithNaN(values.length);
        double previous = 0;
        double seedSum = 0;
        boolean seeded = false;
        for (int i = 0; i < values.length; i++) {
            double value = Double.isNaN(values[i]) ? 0 : values[i];
            if (!seeded) {
                seedSum += value;
                if (i == period - 1) {
                    previous = seedSum / period;
                    out[i] = previous;
                    seeded = true;
                }
            } else {
                previous = previous + (value - previous) / period;
                out[i] = previous;
            }
        }
        return out;
    }

    public static double[] roc(double[] values, int period) {
        double[] out = filledWithNaN(values.length);
        for (int i = period; i < values.length; i++) {
            out[i] = (values[i] / values[i - period] - 1.0) * 100.0;
        }
        return out;
    }

    public static double[] stddev(double[] values, int period) {
        double[] out = filledWithNaN(values.length);
        for (int i = period - 1; i < values.length; i++) {
            double mean = 0;
            for (int j = i - period + 1; j <= i; j++) {
                mean += values[j];
            }
            mean /= period;
            double variance = 0;
            for (int j = i - period + 1; j <= i; j++) {
                variance += (values[j] - mean) * (values[j] - mean);
            }
            out[i] = Math.sqrt(variance / period);
        }
        return out;
    }

    public static double[] zscore(double[] values, int period) {
        double[] mean = sma(values, period);
        double[] deviation = stddev(values, period);
        double[] out = filledWithNaN(values.length);
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(deviation[i]) && deviation[i] != 0) {
                out[i] = (values[i] - mean[i]) / deviation[i];
            }
        }
        return out;
    }

    public static double[] rsi(double[] values, int period) {
        double[] gains = new double[values.length];
        double[] losses = new double[values.length];
        for (int i = 1; i < values.length; i++) {
            double change = values[i] - values[i - 1];
            gains[i] = Math.max(change, 0);
            losses[i] = Math.max(-change, 0);
        }
        double[] averageGain = wilder(gains, period);
        double[] averageLoss = wilder(losses, period);
        double[] out = filledWithNaN(values.length);
        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(averageGain[i])) {
                continue;
            }
            if (averageLoss[i] == 0) {
                out[i] = 100;
                continue;
            }
            double relativeStrength = averageGain[i] / averageLoss[i];
            out[i] = 100.0 - 100.0 / (1.0 + relativeStrength);
        }
        return out;
    }

    public static double[] trueRange(BarSeries bars) {
        int length = bars.size();
        double[] trueRange = filledWithNaN(length);
        for (int i = 0; i < length; i++) {
            if (i == 0) {
                trueRange[i] = bars.high[i] - bars.low[i];
                continue;
            }
            double highLow = bars.high[i] - bars.low[i];
            double highToPreviousClose = Math.abs(bars.high[i] - bars.close[i - 1]);
            double lowToPreviousClose = Math.abs(bars.low[i] - bars.close[i - 1]);
            trueRange[i] = Math.max(highLow, Math.max(highToPreviousClose, lowToPreviousClose));
        }
        return trueRange;
    }

    public static double[] atr(BarSeries bars, int period) {
        return wilder(trueRange(bars), period);
    }

    /** Bollinger Bands: returns {lower, mid, upper}. */
    public static double[][] bollinger(double[] values, int period, double widthInDeviations) {
        double[] mid = sma(values, period);
        double[] deviation = stddev(values, period);
        double[] lower = filledWithNaN(values.length);
        double[] upper = filledWithNaN(values.length);
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(mid[i])) {
                lower[i] = mid[i] - widthInDeviations * deviation[i];
                upper[i] = mid[i] + widthInDeviations * deviation[i];
            }
        }
        return new double[][] {lower, mid, upper};
    }

    /** MACD: returns {macdLine, signalLine, histogram}. */
    public static double[][] macd(double[] values, int fastPeriod, int slowPeriod, int signalPeriod) {
        double[] fastEma = ema(values, fastPeriod);
        double[] slowEma = ema(values, slowPeriod);
        double[] macdLine = filledWithNaN(values.length);
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(fastEma[i]) && !Double.isNaN(slowEma[i])) {
                macdLine[i] = fastEma[i] - slowEma[i];
            }
        }
        double[] signalLine = ema(replaceNaNWithZero(macdLine), signalPeriod);
        double[] histogram = filledWithNaN(values.length);
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(macdLine[i]) && !Double.isNaN(signalLine[i])) {
                histogram[i] = macdLine[i] - signalLine[i];
            }
        }
        return new double[][] {macdLine, signalLine, histogram};
    }

    private static double[] replaceNaNWithZero(double[] values) {
        double[] out = values.clone();
        for (int i = 0; i < out.length; i++) {
            if (Double.isNaN(out[i])) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** ADX: returns {adx, plusDI, minusDI}. */
    public static double[][] adx(BarSeries bars, int period) {
        int length = bars.size();
        double[] plusDM = new double[length];
        double[] minusDM = new double[length];
        for (int i = 1; i < length; i++) {
            double upMove = bars.high[i] - bars.high[i - 1];
            double downMove = bars.low[i - 1] - bars.low[i];
            plusDM[i] = (upMove > downMove && upMove > 0) ? upMove : 0;
            minusDM[i] = (downMove > upMove && downMove > 0) ? downMove : 0;
        }
        double[] atr = wilder(trueRange(bars), period);
        double[] plusDI = filledWithNaN(length);
        double[] minusDI = filledWithNaN(length);
        double[] directionalIndex = filledWithNaN(length);
        double[] smoothedPlusDM = wilder(plusDM, period);
        double[] smoothedMinusDM = wilder(minusDM, period);
        for (int i = 0; i < length; i++) {
            if (Double.isNaN(atr[i]) || atr[i] == 0) {
                continue;
            }
            plusDI[i] = 100 * smoothedPlusDM[i] / atr[i];
            minusDI[i] = 100 * smoothedMinusDM[i] / atr[i];
            double sum = plusDI[i] + minusDI[i];
            directionalIndex[i] = sum == 0 ? 0 : 100 * Math.abs(plusDI[i] - minusDI[i]) / sum;
        }
        return new double[][] {wilder(directionalIndex, period), plusDI, minusDI};
    }

    public static double[] rollingMax(double[] values, int period) {
        double[] out = filledWithNaN(values.length);
        for (int i = period - 1; i < values.length; i++) {
            double max = -Double.MAX_VALUE;
            for (int j = i - period + 1; j <= i; j++) {
                max = Math.max(max, values[j]);
            }
            out[i] = max;
        }
        return out;
    }

    public static double[] rollingMin(double[] values, int period) {
        double[] out = filledWithNaN(values.length);
        for (int i = period - 1; i < values.length; i++) {
            double min = Double.MAX_VALUE;
            for (int j = i - period + 1; j <= i; j++) {
                min = Math.min(min, values[j]);
            }
            out[i] = min;
        }
        return out;
    }

    /** Stochastic: returns {%K, %D}. */
    public static double[][] stochastic(BarSeries bars, int period, int smoothing) {
        double[] highestHigh = rollingMax(bars.high, period);
        double[] lowestLow = rollingMin(bars.low, period);
        double[] percentK = filledWithNaN(bars.size());
        for (int i = 0; i < bars.size(); i++) {
            if (Double.isNaN(highestHigh[i])) {
                continue;
            }
            double range = highestHigh[i] - lowestLow[i];
            percentK[i] = range == 0 ? 50 : 100 * (bars.close[i] - lowestLow[i]) / range;
        }
        return new double[][] {percentK, sma(replaceNaNWithZero(percentK), smoothing)};
    }

    public static double[] williamsR(BarSeries bars, int period) {
        double[] highestHigh = rollingMax(bars.high, period);
        double[] lowestLow = rollingMin(bars.low, period);
        double[] out = filledWithNaN(bars.size());
        for (int i = 0; i < bars.size(); i++) {
            if (Double.isNaN(highestHigh[i])) {
                continue;
            }
            double range = highestHigh[i] - lowestLow[i];
            out[i] = range == 0 ? -50 : -100 * (highestHigh[i] - bars.close[i]) / range;
        }
        return out;
    }

    /** Keltner channel: returns {lower, mid, upper}. */
    public static double[][] keltner(BarSeries bars, int period, double atrMultiplier) {
        double[] mid = ema(bars.close, period);
        double[] atr = atr(bars, period);
        double[] lower = filledWithNaN(bars.size());
        double[] upper = filledWithNaN(bars.size());
        for (int i = 0; i < bars.size(); i++) {
            if (!Double.isNaN(mid[i]) && !Double.isNaN(atr[i])) {
                lower[i] = mid[i] - atrMultiplier * atr[i];
                upper[i] = mid[i] + atrMultiplier * atr[i];
            }
        }
        return new double[][] {lower, mid, upper};
    }

    /** Supertrend direction: +1 uptrend, -1 downtrend. */
    public static double[] supertrend(BarSeries bars, int period, double atrMultiplier) {
        int length = bars.size();
        double[] atr = atr(bars, period);
        double[] direction = filledWithNaN(length);
        double[] finalUpper = new double[length];
        double[] finalLower = new double[length];
        boolean upTrend = true;
        for (int i = 0; i < length; i++) {
            if (i == 0 || Double.isNaN(atr[i])) {
                continue;
            }
            double midPrice = (bars.high[i] + bars.low[i]) / 2;
            double basicUpper = midPrice + atrMultiplier * atr[i];
            double basicLower = midPrice - atrMultiplier * atr[i];
            finalUpper[i] = (bars.close[i - 1] <= finalUpper[i - 1] || finalUpper[i - 1] == 0)
                    ? Math.min(basicUpper, finalUpper[i - 1] == 0 ? basicUpper : finalUpper[i - 1])
                    : basicUpper;
            finalLower[i] =
                    (bars.close[i - 1] >= finalLower[i - 1]) ? Math.max(basicLower, finalLower[i - 1]) : basicLower;
            if (bars.close[i] > finalUpper[i - 1]) {
                upTrend = true;
            } else if (bars.close[i] < finalLower[i - 1]) {
                upTrend = false;
            }
            direction[i] = upTrend ? 1 : -1;
        }
        return direction;
    }

    public static double[] obv(BarSeries bars) {
        double[] out = new double[bars.size()];
        for (int i = 1; i < bars.size(); i++) {
            double sign = Math.signum(bars.close[i] - bars.close[i - 1]);
            out[i] = out[i - 1] + sign * bars.volume[i];
        }
        return out;
    }

    public static double[] vwap(BarSeries bars, int period) {
        double[] out = filledWithNaN(bars.size());
        for (int i = period - 1; i < bars.size(); i++) {
            double priceVolume = 0;
            double totalVolume = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double typicalPrice = (bars.high[j] + bars.low[j] + bars.close[j]) / 3;
                priceVolume += typicalPrice * bars.volume[j];
                totalVolume += bars.volume[j];
            }
            out[i] = totalVolume == 0 ? Double.NaN : priceVolume / totalVolume;
        }
        return out;
    }

    /** 1-D Kalman filter estimate of price level (smooth trend). */
    public static double[] kalman(double[] values, double processNoise, double measurementNoise) {
        double[] out = new double[values.length];
        double estimate = values[0];
        double errorCovariance = 1;
        out[0] = estimate;
        for (int i = 1; i < values.length; i++) {
            double predictedCovariance = errorCovariance + processNoise;
            double gain = predictedCovariance / (predictedCovariance + measurementNoise);
            estimate = estimate + gain * (values[i] - estimate);
            errorCovariance = (1 - gain) * predictedCovariance;
            out[i] = estimate;
        }
        return out;
    }

    private static double[] wma(double[] values, int period) {
        double[] out = filledWithNaN(values.length);
        double weightSum = period * (period + 1) / 2.0;
        for (int i = period - 1; i < values.length; i++) {
            double sum = 0;
            for (int j = 0; j < period; j++) {
                sum += values[i - j] * (period - j);
            }
            out[i] = sum / weightSum;
        }
        return out;
    }

    /** Hull Moving Average: WMA(2*WMA(n/2) - WMA(n), sqrt(n)) — lower lag than SMA/EMA. */
    public static double[] hullMa(double[] values, int period) {
        int halfPeriod = Math.max(1, period / 2);
        int sqrtPeriod = Math.max(1, (int) Math.round(Math.sqrt(period)));
        double[] wmaHalf = wma(values, halfPeriod);
        double[] wmaFull = wma(values, period);
        double[] raw = filledWithNaN(values.length);
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(wmaHalf[i]) && !Double.isNaN(wmaFull[i])) {
                raw[i] = 2 * wmaHalf[i] - wmaFull[i];
            }
        }
        return wma(replaceNaNWithZero(raw), sqrtPeriod);
    }

    /** Double EMA: 2*EMA - EMA(EMA) — less lag than a plain EMA. */
    public static double[] dema(double[] values, int period) {
        double[] ema1 = ema(values, period);
        double[] ema2 = ema(replaceNaNWithZero(ema1), period);
        double[] out = filledWithNaN(values.length);
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(ema1[i]) && !Double.isNaN(ema2[i])) {
                out[i] = 2 * ema1[i] - ema2[i];
            }
        }
        return out;
    }

    /** Triple EMA: 3*EMA1 - 3*EMA2 + EMA3. */
    public static double[] tema(double[] values, int period) {
        double[] ema1 = ema(values, period);
        double[] ema2 = ema(replaceNaNWithZero(ema1), period);
        double[] ema3 = ema(replaceNaNWithZero(ema2), period);
        double[] out = filledWithNaN(values.length);
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(ema1[i]) && !Double.isNaN(ema2[i]) && !Double.isNaN(ema3[i])) {
                out[i] = 3 * ema1[i] - 3 * ema2[i] + ema3[i];
            }
        }
        return out;
    }

    /** Volume-weighted moving average over {@code period} bars. */
    public static double[] vwma(BarSeries bars, int period) {
        double[] out = filledWithNaN(bars.size());
        for (int i = period - 1; i < bars.size(); i++) {
            double priceVolume = 0;
            double totalVolume = 0;
            for (int j = i - period + 1; j <= i; j++) {
                priceVolume += bars.close[j] * bars.volume[j];
                totalVolume += bars.volume[j];
            }
            out[i] = totalVolume == 0 ? Double.NaN : priceVolume / totalVolume;
        }
        return out;
    }

    /** Aroon: returns {up, down}, each 0..100 based on bars since the period's high/low. */
    public static double[][] aroon(BarSeries bars, int period) {
        int length = bars.size();
        double[] up = filledWithNaN(length);
        double[] down = filledWithNaN(length);
        for (int i = period; i < length; i++) {
            int highIndex = i;
            int lowIndex = i;
            double high = bars.high[i];
            double low = bars.low[i];
            for (int j = i - period; j <= i; j++) {
                if (bars.high[j] >= high) {
                    high = bars.high[j];
                    highIndex = j;
                }
                if (bars.low[j] <= low) {
                    low = bars.low[j];
                    lowIndex = j;
                }
            }
            up[i] = 100.0 * (period - (i - highIndex)) / period;
            down[i] = 100.0 * (period - (i - lowIndex)) / period;
        }
        return new double[][] {up, down};
    }

    /** Vortex Indicator: returns {VI+, VI-}. */
    public static double[][] vortex(BarSeries bars, int period) {
        int length = bars.size();
        double[] plusMovement = new double[length];
        double[] minusMovement = new double[length];
        double[] trueRange = trueRange(bars);
        for (int i = 1; i < length; i++) {
            plusMovement[i] = Math.abs(bars.high[i] - bars.low[i - 1]);
            minusMovement[i] = Math.abs(bars.low[i] - bars.high[i - 1]);
        }
        double[] plusVortex = filledWithNaN(length);
        double[] minusVortex = filledWithNaN(length);
        for (int i = period; i < length; i++) {
            double plusSum = 0;
            double minusSum = 0;
            double trueRangeSum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                plusSum += plusMovement[j];
                minusSum += minusMovement[j];
                trueRangeSum += Double.isNaN(trueRange[j]) ? 0 : trueRange[j];
            }
            if (trueRangeSum == 0) {
                continue;
            }
            plusVortex[i] = plusSum / trueRangeSum;
            minusVortex[i] = minusSum / trueRangeSum;
        }
        return new double[][] {plusVortex, minusVortex};
    }

    /** TRIX: rate of change of a triple-smoothed EMA. */
    public static double[] trix(double[] values, int period) {
        double[] ema1 = ema(values, period);
        double[] ema2 = ema(replaceNaNWithZero(ema1), period);
        double[] ema3 = ema(replaceNaNWithZero(ema2), period);
        double[] out = filledWithNaN(values.length);
        for (int i = 1; i < values.length; i++) {
            if (!Double.isNaN(ema3[i]) && !Double.isNaN(ema3[i - 1]) && ema3[i - 1] != 0) {
                out[i] = (ema3[i] / ema3[i - 1] - 1) * 100;
            }
        }
        return out;
    }

    /** Rolling linear regression over the last {@code period} points: returns {fitted value at the current bar, slope}. */
    public static double[][] linreg(double[] values, int period) {
        int length = values.length;
        double[] fitted = filledWithNaN(length);
        double[] slope = filledWithNaN(length);
        double sumX = 0;
        double sumXSquared = 0;
        for (int i = 0; i < period; i++) {
            sumX += i;
            sumXSquared += (double) i * i;
        }
        double meanX = sumX / period;
        double denominator = sumXSquared - period * meanX * meanX;
        for (int i = period - 1; i < length; i++) {
            double sumY = 0;
            double sumXY = 0;
            for (int j = 0; j < period; j++) {
                double value = values[i - period + 1 + j];
                sumY += value;
                sumXY += j * value;
            }
            double meanY = sumY / period;
            double barSlope = denominator == 0 ? 0 : (sumXY - period * meanX * meanY) / denominator;
            slope[i] = barSlope;
            fitted[i] = (meanY - barSlope * meanX) + barSlope * (period - 1);
        }
        return new double[][] {fitted, slope};
    }

    /** Wilder's Parabolic SAR trend flag: +1 while price is above the SAR (uptrend), -1 below. */
    public static double[] psar(BarSeries bars, double step, double maxStep) {
        int length = bars.size();
        double[] direction = filledWithNaN(length);
        if (length < 2) {
            return direction;
        }
        boolean upTrend = true;
        double sar = bars.low[0];
        double extremePoint = bars.high[0];
        double accelerationFactor = step;
        direction[0] = 1;
        for (int i = 1; i < length; i++) {
            sar = sar + accelerationFactor * (extremePoint - sar);
            if (upTrend) {
                sar = Math.min(sar, Math.min(bars.low[i - 1], i >= 2 ? bars.low[i - 2] : bars.low[i - 1]));
                if (bars.low[i] < sar) {
                    upTrend = false;
                    sar = extremePoint;
                    extremePoint = bars.low[i];
                    accelerationFactor = step;
                } else if (bars.high[i] > extremePoint) {
                    extremePoint = bars.high[i];
                    accelerationFactor = Math.min(maxStep, accelerationFactor + step);
                }
            } else {
                sar = Math.max(sar, Math.max(bars.high[i - 1], i >= 2 ? bars.high[i - 2] : bars.high[i - 1]));
                if (bars.high[i] > sar) {
                    upTrend = true;
                    sar = extremePoint;
                    extremePoint = bars.high[i];
                    accelerationFactor = step;
                } else if (bars.low[i] < extremePoint) {
                    extremePoint = bars.low[i];
                    accelerationFactor = Math.min(maxStep, accelerationFactor + step);
                }
            }
            direction[i] = upTrend ? 1 : -1;
        }
        return direction;
    }

    /** Commodity Channel Index. */
    public static double[] cci(BarSeries bars, int period) {
        int length = bars.size();
        double[] typicalPrice = new double[length];
        for (int i = 0; i < length; i++) {
            typicalPrice[i] = (bars.high[i] + bars.low[i] + bars.close[i]) / 3.0;
        }
        double[] typicalPriceSma = sma(typicalPrice, period);
        double[] out = filledWithNaN(length);
        for (int i = period - 1; i < length; i++) {
            double meanDeviation = 0;
            for (int j = i - period + 1; j <= i; j++) {
                meanDeviation += Math.abs(typicalPrice[j] - typicalPriceSma[i]);
            }
            meanDeviation /= period;
            out[i] = meanDeviation == 0 ? 0 : (typicalPrice[i] - typicalPriceSma[i]) / (0.015 * meanDeviation);
        }
        return out;
    }

    /** Money Flow Index (volume-weighted RSI). */
    public static double[] mfi(BarSeries bars, int period) {
        int length = bars.size();
        double[] typicalPrice = new double[length];
        double[] positiveFlow = new double[length];
        double[] negativeFlow = new double[length];
        for (int i = 0; i < length; i++) {
            typicalPrice[i] = (bars.high[i] + bars.low[i] + bars.close[i]) / 3.0;
        }
        for (int i = 1; i < length; i++) {
            double moneyFlow = typicalPrice[i] * bars.volume[i];
            if (typicalPrice[i] > typicalPrice[i - 1]) {
                positiveFlow[i] = moneyFlow;
            } else if (typicalPrice[i] < typicalPrice[i - 1]) {
                negativeFlow[i] = moneyFlow;
            }
        }
        double[] out = filledWithNaN(length);
        for (int i = period; i < length; i++) {
            double positiveSum = 0;
            double negativeSum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                positiveSum += positiveFlow[j];
                negativeSum += negativeFlow[j];
            }
            out[i] = negativeSum == 0 ? 100 : 100 - 100 / (1 + positiveSum / negativeSum);
        }
        return out;
    }

    /** Chande Momentum Oscillator. */
    public static double[] cmo(double[] values, int period) {
        int length = values.length;
        double[] gains = new double[length];
        double[] losses = new double[length];
        for (int i = 1; i < length; i++) {
            double change = values[i] - values[i - 1];
            gains[i] = Math.max(change, 0);
            losses[i] = Math.max(-change, 0);
        }
        double[] out = filledWithNaN(length);
        for (int i = period; i < length; i++) {
            double gainSum = 0;
            double lossSum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                gainSum += gains[j];
                lossSum += losses[j];
            }
            out[i] = (gainSum + lossSum) == 0 ? 0 : 100 * (gainSum - lossSum) / (gainSum + lossSum);
        }
        return out;
    }

    /** Detrended Price Oscillator: price minus an SMA lagged by period/2+1 bars (no look-ahead). */
    public static double[] dpo(double[] values, int period) {
        double[] average = sma(values, period);
        int shift = period / 2 + 1;
        double[] out = filledWithNaN(values.length);
        for (int i = shift; i < values.length; i++) {
            if (!Double.isNaN(average[i - shift])) {
                out[i] = values[i] - average[i - shift];
            }
        }
        return out;
    }

    /** Ultimate Oscillator (Williams): weighted blend of buying pressure over 3 horizons. */
    public static double[] ultimateOscillator(BarSeries bars, int shortPeriod, int midPeriod, int longPeriod) {
        int length = bars.size();
        double[] buyingPressure = new double[length];
        double[] trueRange = new double[length];
        for (int i = 1; i < length; i++) {
            double previousClose = bars.close[i - 1];
            buyingPressure[i] = bars.close[i] - Math.min(bars.low[i], previousClose);
            trueRange[i] = Math.max(bars.high[i], previousClose) - Math.min(bars.low[i], previousClose);
        }
        double[] out = filledWithNaN(length);
        int longestPeriod = Math.max(shortPeriod, Math.max(midPeriod, longPeriod));
        for (int i = longestPeriod; i < length; i++) {
            double shortAverage = pressureRatio(buyingPressure, trueRange, i, shortPeriod);
            double midAverage = pressureRatio(buyingPressure, trueRange, i, midPeriod);
            double longAverage = pressureRatio(buyingPressure, trueRange, i, longPeriod);
            out[i] = 100 * (4 * shortAverage + 2 * midAverage + longAverage) / 7.0;
        }
        return out;
    }

    /** Sum of buying pressure over sum of true range for the {@code period} bars ending at {@code end}. */
    private static double pressureRatio(double[] buyingPressure, double[] trueRange, int end, int period) {
        double pressureSum = 0;
        double rangeSum = 0;
        for (int j = end - period + 1; j <= end; j++) {
            pressureSum += buyingPressure[j];
            rangeSum += trueRange[j];
        }
        return rangeSum == 0 ? 0 : pressureSum / rangeSum;
    }

    /** Elder's Force Index: (close change) * volume, EMA-smoothed. */
    public static double[] forceIndex(BarSeries bars, int period) {
        int length = bars.size();
        double[] raw = new double[length];
        for (int i = 1; i < length; i++) {
            raw[i] = (bars.close[i] - bars.close[i - 1]) * bars.volume[i];
        }
        return ema(raw, period);
    }

    /** Chaikin Money Flow: sum(money-flow-volume, period) / sum(volume, period). */
    public static double[] cmf(BarSeries bars, int period) {
        int length = bars.size();
        double[] moneyFlowVolume = new double[length];
        for (int i = 0; i < length; i++) {
            double range = bars.high[i] - bars.low[i];
            double multiplier =
                    range == 0 ? 0 : ((bars.close[i] - bars.low[i]) - (bars.high[i] - bars.close[i])) / range;
            moneyFlowVolume[i] = multiplier * bars.volume[i];
        }
        double[] out = filledWithNaN(length);
        for (int i = period - 1; i < length; i++) {
            double flowSum = 0;
            double volumeSum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                flowSum += moneyFlowVolume[j];
                volumeSum += bars.volume[j];
            }
            out[i] = volumeSum == 0 ? 0 : flowSum / volumeSum;
        }
        return out;
    }

    /** Ease of Movement: price advance per unit of range/volume, SMA-smoothed. */
    public static double[] emv(BarSeries bars, int period) {
        int length = bars.size();
        double[] raw = new double[length];
        for (int i = 1; i < length; i++) {
            double distance = (bars.high[i] + bars.low[i]) / 2.0 - (bars.high[i - 1] + bars.low[i - 1]) / 2.0;
            double range = bars.high[i] - bars.low[i];
            raw[i] = (bars.volume[i] == 0 || range == 0) ? 0 : distance * range / bars.volume[i];
        }
        return sma(raw, period);
    }

    /** Accumulation/Distribution line: cumulative close-location-weighted volume. */
    public static double[] adLine(BarSeries bars) {
        int length = bars.size();
        double[] out = new double[length];
        for (int i = 0; i < length; i++) {
            double range = bars.high[i] - bars.low[i];
            double multiplier =
                    range == 0 ? 0 : ((bars.close[i] - bars.low[i]) - (bars.high[i] - bars.close[i])) / range;
            out[i] = (i == 0 ? 0 : out[i - 1]) + multiplier * bars.volume[i];
        }
        return out;
    }

    /** Volume Price Trend: cumulative volume weighted by percentage price change. */
    public static double[] vpt(BarSeries bars) {
        int length = bars.size();
        double[] out = new double[length];
        for (int i = 1; i < length; i++) {
            double change = bars.close[i - 1] == 0 ? 0 : (bars.close[i] - bars.close[i - 1]) / bars.close[i - 1];
            out[i] = out[i - 1] + change * bars.volume[i];
        }
        return out;
    }

    /**
     * LazyBear's WaveTrend oscillator: {@code channelLen} smooths the (H+L+C)/3 price and its mean deviation into a CCI-like ratio, {@code avgLen} smooths that into WT1,
     * and WT2 is a 4-bar SMA of WT1 (read like a MACD/signal-line cross, gated by ±60 zones); returns {@code {wt1, wt2}}.
     */
    public static double[][] waveTrend(BarSeries bars, int channelLen, int avgLen) {
        int length = bars.size();
        double[] averagePrice = new double[length];
        for (int i = 0; i < length; i++) {
            averagePrice[i] = (bars.high[i] + bars.low[i] + bars.close[i]) / 3.0;
        }
        double[] smoothedPrice = ema(averagePrice, channelLen);
        double[] absoluteDeviation = new double[length];
        for (int i = 0; i < length; i++) {
            absoluteDeviation[i] = Math.abs(averagePrice[i] - smoothedPrice[i]); // NaN while smoothedPrice warms up
        }
        double[] meanDeviation = fromFirstValid(absoluteDeviation, series -> ema(series, channelLen));
        double[] channelIndex = new double[length];
        for (int i = 0; i < length; i++) {
            channelIndex[i] =
                    meanDeviation[i] == 0 ? 0 : (averagePrice[i] - smoothedPrice[i]) / (0.015 * meanDeviation[i]);
        }
        double[] wt1 = fromFirstValid(channelIndex, series -> ema(series, avgLen));
        double[] wt2 = fromFirstValid(wt1, series -> sma(series, 4));
        return new double[][] {wt1, wt2};
    }

    /**
     * Applies a smoother from the first non-NaN value on: {@link #ema} and {@link #sma} seed from the first {@code period} inputs,
     * so a NaN warm-up prefix from an earlier smoothing stage would otherwise poison every value after it.
     */
    public static double[] fromFirstValid(double[] values, UnaryOperator<double[]> smoother) {
        int first = 0;
        while (first < values.length && Double.isNaN(values[first])) {
            first++;
        }
        if (first == 0) {
            return smoother.apply(values);
        }
        double[] out = filledWithNaN(values.length);
        double[] tail = smoother.apply(Arrays.copyOfRange(values, first, values.length));
        System.arraycopy(tail, 0, out, first, tail.length);
        return out;
    }
}
