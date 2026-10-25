package com.quant.finance.decision.strategy.impl;

import com.quant.finance.decision.strategy.AbstractStrategy;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.Indicators;
import java.util.Map;

class GapReversion extends AbstractStrategy {
    public String name() {
        return "gap_reversion";
    }

    public String category() {
        return "pattern";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Fade large overnight gaps: short gap-ups, buy gap-downs.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("gap", 0.03);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double gapThreshold = param(params, "gap");
        double[] signal = new double[bars.size()];
        for (int i = 1; i < bars.size(); i++) {
            double gap = bars.open[i] / bars.close[i - 1] - 1;
            if (gap > gapThreshold) {
                signal[i] = -1;
            } else if (gap < -gapThreshold) {
                signal[i] = 1;
            }
        }
        return clean(signal);
    }
}

class InsideBarBreakout extends AbstractStrategy {
    public String name() {
        return "inside_bar_breakout";
    }

    public String category() {
        return "pattern";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Inside bar (range within the prior bar) then a break of that range.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("exitMa", 10.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        boolean[] insideBar = new boolean[length];
        for (int i = 1; i < length; i++) {
            insideBar[i] = bars.high[i] <= bars.high[i - 1] && bars.low[i] >= bars.low[i - 1];
        }
        boolean[] armed = previous(insideBar);
        double[] previousHigh = previous(bars.high);
        double[] previousLow = previous(bars.low);
        boolean[] longEntry = and(armed, above(bars.close, previousHigh));
        boolean[] shortEntry = and(armed, below(bars.close, previousLow));
        double[] exitMa = Indicators.sma(bars.close, intParam(params, "exitMa"));
        return clean(hold(longEntry, below(bars.close, exitMa), shortEntry, above(bars.close, exitMa)));
    }
}

class OutsideBarReversal extends AbstractStrategy {
    public String name() {
        return "outside_bar_reversal";
    }

    public String category() {
        return "pattern";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "Outside (engulfing-range) bar: fade the close's position within its own range.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("exitBars", 3.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        int exitBars = intParam(params, "exitBars");
        double[] signal = new double[length];
        int barsLeftToHold = 0;
        for (int i = 1; i < length; i++) {
            boolean outsideBar = bars.high[i] > bars.high[i - 1] && bars.low[i] < bars.low[i - 1];
            double range = bars.high[i] - bars.low[i];
            if (outsideBar && range > 0) {
                double closeLocation = (bars.close[i] - bars.low[i]) / range;
                if (closeLocation > 0.7) {
                    signal[i] = -1;
                    barsLeftToHold = exitBars;
                } else if (closeLocation < 0.3) {
                    signal[i] = 1;
                    barsLeftToHold = exitBars;
                } else {
                    barsLeftToHold = 0;
                }
            } else if (barsLeftToHold > 0) {
                signal[i] = signal[i - 1];
                barsLeftToHold--;
            }
        }
        return clean(signal);
    }
}

class ConsecutiveTrendBars extends AbstractStrategy {
    public String name() {
        return "consecutive_trend_bars";
    }

    public String category() {
        return "pattern";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "N consecutive higher highs+lows (or lower highs+lows) triggers a trade.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 3.0, "exitMa", 10.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        int requiredStreak = intParam(params, "n");
        boolean[] higherBar = new boolean[length];
        boolean[] lowerBar = new boolean[length];
        for (int i = 1; i < length; i++) {
            higherBar[i] = bars.high[i] > bars.high[i - 1] && bars.low[i] > bars.low[i - 1];
            lowerBar[i] = bars.high[i] < bars.high[i - 1] && bars.low[i] < bars.low[i - 1];
        }
        boolean[] longEntry = new boolean[length];
        boolean[] shortEntry = new boolean[length];
        int upStreak = 0;
        int downStreak = 0;
        for (int i = 0; i < length; i++) {
            upStreak = higherBar[i] ? upStreak + 1 : 0;
            downStreak = lowerBar[i] ? downStreak + 1 : 0;
            longEntry[i] = upStreak >= requiredStreak;
            shortEntry[i] = downStreak >= requiredStreak;
        }
        double[] exitMa = Indicators.sma(bars.close, intParam(params, "exitMa"));
        return clean(hold(longEntry, below(bars.close, exitMa), shortEntry, above(bars.close, exitMa)));
    }
}

class HammerReversal extends AbstractStrategy {
    public String name() {
        return "hammer_reversal";
    }

    public String category() {
        return "pattern";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "A long wick at a rolling extreme (hammer/shooting-star) fades next bar.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("n", 10.0, "wickRatio", 2.0, "exitBars", 3.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        int period = intParam(params, "n");
        int exitBars = intParam(params, "exitBars");
        double wickRatio = param(params, "wickRatio");
        double[] lowestLow = Indicators.rollingMin(bars.low, period);
        double[] highestHigh = Indicators.rollingMax(bars.high, period);
        double[] signal = new double[length];
        int barsLeftToHold = 0;
        for (int i = 0; i < length; i++) {
            double body = Math.abs(bars.close[i] - bars.open[i]);
            double lowerWick = Math.min(bars.open[i], bars.close[i]) - bars.low[i];
            double upperWick = bars.high[i] - Math.max(bars.open[i], bars.close[i]);
            boolean atLow = !Double.isNaN(lowestLow[i]) && bars.low[i] <= lowestLow[i];
            boolean atHigh = !Double.isNaN(highestHigh[i]) && bars.high[i] >= highestHigh[i];
            if (atLow && body > 0 && lowerWick > body * wickRatio) {
                signal[i] = 1;
                barsLeftToHold = exitBars;
            } else if (atHigh && body > 0 && upperWick > body * wickRatio) {
                signal[i] = -1;
                barsLeftToHold = exitBars;
            } else if (barsLeftToHold > 0) {
                signal[i] = signal[i - 1];
                barsLeftToHold--;
            }
        }
        return clean(signal);
    }
}

class WideRangeBarFade extends AbstractStrategy {
    public String name() {
        return "wide_range_bar_fade";
    }

    public String category() {
        return "pattern";
    }

    public String direction() {
        return "long_short";
    }

    public String description() {
        return "An exhaustion bar (range >> ATR) closing near its extreme fades next bar.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("atrN", 14.0, "mult", 2.5);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        double[] atr = Indicators.atr(bars, intParam(params, "atrN"));
        double multiplier = param(params, "mult");
        int length = bars.size();
        double[] signal = new double[length];
        for (int i = 0; i < length; i++) {
            double range = bars.high[i] - bars.low[i];
            if (Double.isNaN(atr[i]) || range == 0 || range <= atr[i] * multiplier) {
                continue;
            }
            double closeLocation = (bars.close[i] - bars.low[i]) / range;
            if (closeLocation > 0.8) {
                signal[i] = -1;
            } else if (closeLocation < 0.2) {
                signal[i] = 1;
            }
        }
        return clean(signal);
    }
}
