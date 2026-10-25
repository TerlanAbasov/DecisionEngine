package com.quant.finance.decision.strategy.impl;

import java.util.Arrays;

/**
 * Market-neutral statistical arbitrage: rolling hedge ratio on log prices, trading the spread's z-score back to the mean;
 * separate from the single-asset catalog because it needs two aligned price series.
 */
public final class PairsStrategy {
    public final int window;
    public final double entry;
    public final double exit;

    public PairsStrategy(int window, double entry, double exit) {
        this.window = window;
        this.entry = entry;
        this.exit = exit;
    }

    /** Returns {posA, posB} target weights in {-1,0,1} for the two legs. */
    public double[][] signals(double[] closeA, double[] closeB) {
        int length = closeA.length;
        double[] logA = new double[length];
        double[] logB = new double[length];
        for (int i = 0; i < length; i++) {
            logA[i] = Math.log(closeA[i]);
            logB[i] = Math.log(closeB[i]);
        }
        double[] spread = new double[length];
        Arrays.fill(spread, Double.NaN);
        double[] zScore = new double[length];
        Arrays.fill(zScore, Double.NaN);
        for (int i = window - 1; i < length; i++) {
            double meanLogA = 0;
            double meanLogB = 0;
            for (int j = i - window + 1; j <= i; j++) {
                meanLogA += logA[j];
                meanLogB += logB[j];
            }
            meanLogA /= window;
            meanLogB /= window;
            double covariance = 0;
            double variance = 0;
            for (int j = i - window + 1; j <= i; j++) {
                covariance += (logA[j] - meanLogA) * (logB[j] - meanLogB);
                variance += (logB[j] - meanLogB) * (logB[j] - meanLogB);
            }
            double beta = variance == 0 ? 0 : covariance / variance;
            spread[i] = logA[i] - beta * logB[i];
        }
        for (int i = window - 1; i < length; i++) {
            if (Double.isNaN(spread[i])) {
                continue;
            }
            double spreadMean = 0;
            int validCount = 0;
            for (int j = Math.max(0, i - window + 1); j <= i; j++) {
                if (!Double.isNaN(spread[j])) {
                    spreadMean += spread[j];
                    validCount++;
                }
            }
            spreadMean /= validCount;
            double spreadDeviation = 0;
            for (int j = Math.max(0, i - window + 1); j <= i; j++) {
                if (!Double.isNaN(spread[j])) {
                    spreadDeviation += (spread[j] - spreadMean) * (spread[j] - spreadMean);
                }
            }
            spreadDeviation = Math.sqrt(spreadDeviation / validCount);
            zScore[i] = spreadDeviation == 0 ? 0 : (spread[i] - spreadMean) / spreadDeviation;
        }
        double[] spreadPosition = new double[length];
        int state = 0;
        for (int i = 0; i < length; i++) {
            if (!Double.isNaN(zScore[i])) {
                if (state == 0) {
                    if (zScore[i] < -entry) {
                        state = 1;
                    } else if (zScore[i] > entry) {
                        state = -1;
                    }
                } else if (state == 1 && zScore[i] > -exit) {
                    state = 0;
                } else if (state == -1 && zScore[i] < exit) {
                    state = 0;
                }
            }
            spreadPosition[i] = state;
        }
        double[] positionA = spreadPosition.clone();
        double[] positionB = new double[length];
        for (int i = 0; i < length; i++) {
            positionB[i] = -spreadPosition[i];
        }
        return new double[][] {positionA, positionB};
    }
}
