package com.quantplat.strategy.impl;

import com.quantplat.strategy.AbstractStrategy;
import com.quantplat.strategy.BarSeries;
import com.quantplat.strategy.Indicators;
import java.util.Map;

class GapReversion extends AbstractStrategy {
    public String name() { return "gap_reversion"; }
    public String category() { return "pattern"; }
    public String direction() { return "long_short"; }
    public String description() { return "Fade large overnight gaps: short gap-ups, buy gap-downs."; }
    public Map<String, Double> defaultParams() { return Map.of("gap", 0.03); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double g = p(pr, "gap");
        double[] sig = new double[b.size()];
        for (int i = 1; i < b.size(); i++) {
            double gap = b.open[i] / b.close[i - 1] - 1;
            if (gap > g) sig[i] = -1;
            else if (gap < -g) sig[i] = 1;
        }
        return clean(sig);
    }
}

class InsideBarBreakout extends AbstractStrategy {
    public String name() { return "inside_bar_breakout"; }
    public String category() { return "pattern"; }
    public String direction() { return "long_short"; }
    public String description() { return "Inside bar (range within the prior bar) then a break of that range."; }
    public Map<String, Double> defaultParams() { return Map.of("exitMa", 10.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int len = b.size();
        boolean[] inside = new boolean[len];
        for (int i = 1; i < len; i++) inside[i] = b.high[i] <= b.high[i - 1] && b.low[i] >= b.low[i - 1];
        boolean[] armed = shift1(inside);
        double[] prevHi = shift1(b.high), prevLo = shift1(b.low);
        boolean[] in = and(armed, gt(b.close, prevHi));
        boolean[] si = and(armed, lt(b.close, prevLo));
        double[] exitMa = Indicators.sma(b.close, pi(pr, "exitMa"));
        return clean(hold(in, lt(b.close, exitMa), si, gt(b.close, exitMa)));
    }
}

class OutsideBarReversal extends AbstractStrategy {
    public String name() { return "outside_bar_reversal"; }
    public String category() { return "pattern"; }
    public String direction() { return "long_short"; }
    public String description() { return "Outside (engulfing-range) bar: fade the close's position within its own range."; }
    public Map<String, Double> defaultParams() { return Map.of("exitBars", 3.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int len = b.size(), exitBars = pi(pr, "exitBars");
        double[] sig = new double[len];
        int cooldown = 0;
        for (int i = 1; i < len; i++) {
            boolean outside = b.high[i] > b.high[i - 1] && b.low[i] < b.low[i - 1];
            double range = b.high[i] - b.low[i];
            if (outside && range > 0) {
                double closePos = (b.close[i] - b.low[i]) / range;
                if (closePos > 0.7) { sig[i] = -1; cooldown = exitBars; }
                else if (closePos < 0.3) { sig[i] = 1; cooldown = exitBars; }
                else cooldown = 0;
            } else if (cooldown > 0) {
                sig[i] = sig[i - 1];
                cooldown--;
            }
        }
        return clean(sig);
    }
}

class ConsecutiveTrendBars extends AbstractStrategy {
    public String name() { return "consecutive_trend_bars"; }
    public String category() { return "pattern"; }
    public String direction() { return "long_short"; }
    public String description() { return "N consecutive higher highs+lows (or lower highs+lows) triggers a trade."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 3.0, "exitMa", 10.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int len = b.size(), n = pi(pr, "n");
        boolean[] higher = new boolean[len], lower = new boolean[len];
        for (int i = 1; i < len; i++) {
            higher[i] = b.high[i] > b.high[i - 1] && b.low[i] > b.low[i - 1];
            lower[i] = b.high[i] < b.high[i - 1] && b.low[i] < b.low[i - 1];
        }
        boolean[] in = new boolean[len], si = new boolean[len];
        int upStreak = 0, dnStreak = 0;
        for (int i = 0; i < len; i++) {
            upStreak = higher[i] ? upStreak + 1 : 0;
            dnStreak = lower[i] ? dnStreak + 1 : 0;
            in[i] = upStreak >= n;
            si[i] = dnStreak >= n;
        }
        double[] exitMa = Indicators.sma(b.close, pi(pr, "exitMa"));
        return clean(hold(in, lt(b.close, exitMa), si, gt(b.close, exitMa)));
    }
}

class HammerReversal extends AbstractStrategy {
    public String name() { return "hammer_reversal"; }
    public String category() { return "pattern"; }
    public String direction() { return "long_short"; }
    public String description() { return "A long wick at a rolling extreme (hammer/shooting-star) fades next bar."; }
    public Map<String, Double> defaultParams() { return Map.of("n", 10.0, "wickRatio", 2.0, "exitBars", 3.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int len = b.size(), n = pi(pr, "n"), exitBars = pi(pr, "exitBars");
        double wickRatio = p(pr, "wickRatio");
        double[] lo = Indicators.rollingMin(b.low, n), hi = Indicators.rollingMax(b.high, n);
        double[] sig = new double[len];
        int cooldown = 0;
        for (int i = 0; i < len; i++) {
            double body = Math.abs(b.close[i] - b.open[i]);
            double lowerWick = Math.min(b.open[i], b.close[i]) - b.low[i];
            double upperWick = b.high[i] - Math.max(b.open[i], b.close[i]);
            boolean atLow = !nan(lo[i]) && b.low[i] <= lo[i];
            boolean atHigh = !nan(hi[i]) && b.high[i] >= hi[i];
            if (atLow && body > 0 && lowerWick > body * wickRatio) { sig[i] = 1; cooldown = exitBars; }
            else if (atHigh && body > 0 && upperWick > body * wickRatio) { sig[i] = -1; cooldown = exitBars; }
            else if (cooldown > 0) { sig[i] = sig[i - 1]; cooldown--; }
        }
        return clean(sig);
    }
}

class WideRangeBarFade extends AbstractStrategy {
    public String name() { return "wide_range_bar_fade"; }
    public String category() { return "pattern"; }
    public String direction() { return "long_short"; }
    public String description() { return "An exhaustion bar (range >> ATR) closing near its extreme fades next bar."; }
    public Map<String, Double> defaultParams() { return Map.of("atrN", 14.0, "mult", 2.5); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        double[] atr = Indicators.atr(b, pi(pr, "atrN"));
        double mult = p(pr, "mult");
        int len = b.size();
        double[] sig = new double[len];
        for (int i = 0; i < len; i++) {
            double range = b.high[i] - b.low[i];
            if (nan(atr[i]) || range == 0 || range <= atr[i] * mult) continue;
            double closePos = (b.close[i] - b.low[i]) / range;
            if (closePos > 0.8) sig[i] = -1;
            else if (closePos < 0.2) sig[i] = 1;
        }
        return clean(sig);
    }
}
