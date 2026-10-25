package com.quant.finance.decision.strategy.impl;

import com.quant.finance.decision.strategy.AbstractStrategy;
import com.quant.finance.decision.strategy.BarSeries;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;

class SeasonalityTom extends AbstractStrategy {
    public String name() {
        return "seasonality_tom";
    }

    public String category() {
        return "seasonal";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "Turn-of-month: hold the last N and first M trading days of each month.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("pre", 1.0, "post", 3.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        int daysBeforeMonthEnd = intParam(params, "pre");
        int daysAfterMonthStart = intParam(params, "post");
        int[] monthKey = new int[length];
        for (int i = 0; i < length; i++) {
            ZonedDateTime date = bars.date[i].atZone(ZoneOffset.UTC);
            monthKey[i] = date.getYear() * 12 + date.getMonthValue();
        }
        int[] barsIntoMonth = new int[length];
        int[] barsToMonthEnd = new int[length];
        for (int i = 0; i < length; i++) {
            barsIntoMonth[i] = (i == 0 || monthKey[i] != monthKey[i - 1]) ? 0 : barsIntoMonth[i - 1] + 1;
        }
        for (int i = length - 1; i >= 0; i--) {
            barsToMonthEnd[i] = (i == length - 1 || monthKey[i] != monthKey[i + 1]) ? 0 : barsToMonthEnd[i + 1] + 1;
        }
        double[] signal = new double[length];
        for (int i = 0; i < length; i++) {
            signal[i] = (barsIntoMonth[i] < daysAfterMonthStart || barsToMonthEnd[i] < daysBeforeMonthEnd) ? 1 : 0;
        }
        return clean(signal);
    }
}

class DayOfWeekFilter extends AbstractStrategy {
    public String name() {
        return "day_of_week_filter";
    }

    public String category() {
        return "seasonal";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "Stay long every weekday except a configurable historically-weak day.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("avoidDow", 5.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int avoidedDayOfWeek = intParam(params, "avoidDow");
        int length = bars.size();
        double[] signal = new double[length];
        for (int i = 0; i < length; i++) {
            int dayOfWeek = bars.date[i].atZone(ZoneOffset.UTC).getDayOfWeek().getValue();
            signal[i] = dayOfWeek == avoidedDayOfWeek ? 0 : 1;
        }
        return clean(signal);
    }
}

class JanuaryEffect extends AbstractStrategy {
    public String name() {
        return "january_effect";
    }

    public String category() {
        return "seasonal";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "Long only in January (small-cap January-effect seasonality).";
    }

    public Map<String, Double> defaultParams() {
        return Map.of();
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        double[] signal = new double[length];
        for (int i = 0; i < length; i++) {
            signal[i] = bars.date[i].atZone(ZoneOffset.UTC).getMonthValue() == 1 ? 1 : 0;
        }
        return clean(signal);
    }
}

class SellInMay extends AbstractStrategy {
    public String name() {
        return "sell_in_may";
    }

    public String category() {
        return "seasonal";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "\"Sell in May and go away\": long November-April, flat May-October.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of();
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        double[] signal = new double[length];
        for (int i = 0; i < length; i++) {
            int month = bars.date[i].atZone(ZoneOffset.UTC).getMonthValue();
            signal[i] = (month >= 11 || month <= 4) ? 1 : 0;
        }
        return clean(signal);
    }
}

class QuarterEndEffect extends AbstractStrategy {
    public String name() {
        return "quarter_end_effect";
    }

    public String category() {
        return "seasonal";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "Window-dressing effect: hold the last N trading days of each quarter.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("days", 5.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        int daysBeforeQuarterEnd = intParam(params, "days");
        int[] quarterKey = new int[length];
        for (int i = 0; i < length; i++) {
            ZonedDateTime date = bars.date[i].atZone(ZoneOffset.UTC);
            quarterKey[i] = date.getYear() * 4 + (date.getMonthValue() - 1) / 3;
        }
        int[] barsToQuarterEnd = new int[length];
        for (int i = length - 1; i >= 0; i--) {
            barsToQuarterEnd[i] =
                    (i == length - 1 || quarterKey[i] != quarterKey[i + 1]) ? 0 : barsToQuarterEnd[i + 1] + 1;
        }
        double[] signal = new double[length];
        for (int i = 0; i < length; i++) {
            signal[i] = barsToQuarterEnd[i] < daysBeforeQuarterEnd ? 1 : 0;
        }
        return clean(signal);
    }
}

class SantaClausRally extends AbstractStrategy {
    public String name() {
        return "santa_claus_rally";
    }

    public String category() {
        return "seasonal";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "Long the last 5 trading days of December plus the first 2 of January.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("preDays", 5.0, "postDays", 2.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int length = bars.size();
        int daysBeforeYearEnd = intParam(params, "preDays");
        int daysAfterYearStart = intParam(params, "postDays");
        double[] signal = new double[length];
        for (int i = 0; i < length; i++) {
            ZonedDateTime date = bars.date[i].atZone(ZoneOffset.UTC);
            if (date.getMonthValue() == 12) {
                int barsToYearEnd = 0;
                for (int j = i + 1;
                        j < length && bars.date[j].atZone(ZoneOffset.UTC).getMonthValue() == 12;
                        j++) {
                    barsToYearEnd++;
                }
                if (barsToYearEnd < daysBeforeYearEnd) {
                    signal[i] = 1;
                }
            } else if (date.getMonthValue() == 1) {
                int barsIntoYear = 0;
                for (int j = i - 1; j >= 0; j--) {
                    ZonedDateTime earlierDate = bars.date[j].atZone(ZoneOffset.UTC);
                    if (earlierDate.getYear() == date.getYear() && earlierDate.getMonthValue() == 1) {
                        barsIntoYear++;
                    } else {
                        break;
                    }
                }
                if (barsIntoYear < daysAfterYearStart) {
                    signal[i] = 1;
                }
            }
        }
        return clean(signal);
    }
}

class MidMonthEffect extends AbstractStrategy {
    public String name() {
        return "mid_month_effect";
    }

    public String category() {
        return "seasonal";
    }

    public String direction() {
        return "long_only";
    }

    public String description() {
        return "Long only during calendar days 10-20 of each month.";
    }

    public Map<String, Double> defaultParams() {
        return Map.of("from", 10.0, "to", 20.0);
    }

    public double[] generateSignals(BarSeries bars, Map<String, Double> params) {
        int fromDay = intParam(params, "from");
        int toDay = intParam(params, "to");
        int length = bars.size();
        double[] signal = new double[length];
        for (int i = 0; i < length; i++) {
            int dayOfMonth = bars.date[i].atZone(ZoneOffset.UTC).getDayOfMonth();
            signal[i] = (dayOfMonth >= fromDay && dayOfMonth <= toDay) ? 1 : 0;
        }
        return clean(signal);
    }
}
