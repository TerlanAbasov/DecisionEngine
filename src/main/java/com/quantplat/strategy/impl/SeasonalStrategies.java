package com.quantplat.strategy.impl;

import com.quantplat.strategy.AbstractStrategy;
import com.quantplat.strategy.BarSeries;
import java.time.LocalDate;
import java.util.Map;

class SeasonalityTom extends AbstractStrategy {
    public String name() { return "seasonality_tom"; }
    public String category() { return "seasonal"; }
    public String direction() { return "long_only"; }
    public String description() { return "Turn-of-month: hold the last N and first M trading days of each month."; }
    public Map<String, Double> defaultParams() { return Map.of("pre", 1.0, "post", 3.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = b.size(), pre = pi(pr, "pre"), post = pi(pr, "post");
        int[] ym = new int[n];
        for (int i = 0; i < n; i++) ym[i] = b.date[i].getYear() * 12 + b.date[i].getMonthValue();
        int[] posIn = new int[n], fromEnd = new int[n];
        for (int i = 0; i < n; i++) posIn[i] = (i == 0 || ym[i] != ym[i - 1]) ? 0 : posIn[i - 1] + 1;
        for (int i = n - 1; i >= 0; i--) fromEnd[i] = (i == n - 1 || ym[i] != ym[i + 1]) ? 0 : fromEnd[i + 1] + 1;
        double[] sig = new double[n];
        for (int i = 0; i < n; i++) sig[i] = (posIn[i] < post || fromEnd[i] < pre) ? 1 : 0;
        return clean(sig);
    }
}

class DayOfWeekFilter extends AbstractStrategy {
    public String name() { return "day_of_week_filter"; }
    public String category() { return "seasonal"; }
    public String direction() { return "long_only"; }
    public String description() { return "Stay long every weekday except a configurable historically-weak day."; }
    public Map<String, Double> defaultParams() { return Map.of("avoidDow", 5.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int avoid = pi(pr, "avoidDow");
        int len = b.size();
        double[] sig = new double[len];
        for (int i = 0; i < len; i++)
            sig[i] = b.date[i].getDayOfWeek().getValue() == avoid ? 0 : 1;
        return clean(sig);
    }
}

class JanuaryEffect extends AbstractStrategy {
    public String name() { return "january_effect"; }
    public String category() { return "seasonal"; }
    public String direction() { return "long_only"; }
    public String description() { return "Long only in January (small-cap January-effect seasonality)."; }
    public Map<String, Double> defaultParams() { return Map.of(); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int len = b.size();
        double[] sig = new double[len];
        for (int i = 0; i < len; i++) sig[i] = b.date[i].getMonthValue() == 1 ? 1 : 0;
        return clean(sig);
    }
}

class SellInMay extends AbstractStrategy {
    public String name() { return "sell_in_may"; }
    public String category() { return "seasonal"; }
    public String direction() { return "long_only"; }
    public String description() { return "\"Sell in May and go away\": long November-April, flat May-October."; }
    public Map<String, Double> defaultParams() { return Map.of(); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int len = b.size();
        double[] sig = new double[len];
        for (int i = 0; i < len; i++) {
            int m = b.date[i].getMonthValue();
            sig[i] = (m >= 11 || m <= 4) ? 1 : 0;
        }
        return clean(sig);
    }
}

class QuarterEndEffect extends AbstractStrategy {
    public String name() { return "quarter_end_effect"; }
    public String category() { return "seasonal"; }
    public String direction() { return "long_only"; }
    public String description() { return "Window-dressing effect: hold the last N trading days of each quarter."; }
    public Map<String, Double> defaultParams() { return Map.of("days", 5.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = b.size(), days = pi(pr, "days");
        int[] qtr = new int[n];
        for (int i = 0; i < n; i++) qtr[i] = b.date[i].getYear() * 4 + (b.date[i].getMonthValue() - 1) / 3;
        int[] fromEnd = new int[n];
        for (int i = n - 1; i >= 0; i--) fromEnd[i] = (i == n - 1 || qtr[i] != qtr[i + 1]) ? 0 : fromEnd[i + 1] + 1;
        double[] sig = new double[n];
        for (int i = 0; i < n; i++) sig[i] = fromEnd[i] < days ? 1 : 0;
        return clean(sig);
    }
}

class SantaClausRally extends AbstractStrategy {
    public String name() { return "santa_claus_rally"; }
    public String category() { return "seasonal"; }
    public String direction() { return "long_only"; }
    public String description() { return "Long the last 5 trading days of December plus the first 2 of January."; }
    public Map<String, Double> defaultParams() { return Map.of("preDays", 5.0, "postDays", 2.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int n = b.size(), pre = pi(pr, "preDays"), post = pi(pr, "postDays");
        double[] sig = new double[n];
        for (int i = 0; i < n; i++) {
            LocalDate d = b.date[i];
            if (d.getMonthValue() == 12) {
                int fromEnd = 0;
                for (int j = i + 1; j < n && b.date[j].getMonthValue() == 12; j++) fromEnd++;
                if (fromEnd < pre) sig[i] = 1;
            } else if (d.getMonthValue() == 1) {
                int posIn = 0;
                for (int j = i - 1; j >= 0 && b.date[j].getYear() == d.getYear() && b.date[j].getMonthValue() == 1; j--) posIn++;
                if (posIn < post) sig[i] = 1;
            }
        }
        return clean(sig);
    }
}

class MidMonthEffect extends AbstractStrategy {
    public String name() { return "mid_month_effect"; }
    public String category() { return "seasonal"; }
    public String direction() { return "long_only"; }
    public String description() { return "Long only during calendar days 10-20 of each month."; }
    public Map<String, Double> defaultParams() { return Map.of("from", 10.0, "to", 20.0); }
    public double[] generateSignals(BarSeries b, Map<String, Double> pr) {
        int from = pi(pr, "from"), to = pi(pr, "to"), n = b.size();
        double[] sig = new double[n];
        for (int i = 0; i < n; i++) {
            int dom = b.date[i].getDayOfMonth();
            sig[i] = (dom >= from && dom <= to) ? 1 : 0;
        }
        return clean(sig);
    }
}
