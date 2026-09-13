package com.quant.finance.execution.engine;

/**
 * Resampling target for a backtest. Bars are stored at whatever native granularity
 * {@code quantplat.alpaca.timeframe} fetched; a backtest can aggregate them UP to a
 * coarser {@code Timeframe} (never finer — you cannot invent sub-bars, so choosing a
 * frame finer than the stored data just runs on the stored bars).
 *
 * <p>{@link #NATIVE} runs on the stored bars untouched. Sub-day frames all divide
 * evenly into 24h, so their buckets align to UTC midnight; {@code W1}/{@code MN}/
 * {@code Q1}/{@code Y1} are calendar-bucketed.
 */
public enum Timeframe {
    NATIVE("Native", 0),
    M5("5 Min", 300),
    M15("15 Min", 900),
    M30("30 Min", 1_800),
    H1("1 Hour", 3_600),
    H2("2 Hour", 7_200),
    H3("3 Hour", 10_800),
    H4("4 Hour", 14_400),
    H6("6 Hour", 21_600),
    H8("8 Hour", 28_800),
    H12("12 Hour", 43_200),
    D1("1 Day", 86_400),
    W1("1 Week", 0),           // calendar week (ISO, Monday-anchored)
    MN("1 Month", 0),          // calendar month
    Q1("1 Quarter", 0),        // calendar quarter
    Y1("1 Year", 0);           // calendar year

    public final String label;
    /** Bucket width in seconds for fixed-width frames; 0 for NATIVE and calendar frames. */
    public final int seconds;

    Timeframe(String label, int seconds) {
        this.label = label;
        this.seconds = seconds;
    }

    /** Lenient parse: accepts enum names and common aliases ("1D", "daily", "1Day", "1H", "weekly"…). */
    public static Timeframe from(String s) {
        if (s == null || s.isBlank()) return NATIVE;
        String k = s.trim().toUpperCase().replace(" ", "").replace("-", "").replace("_", "");
        return switch (k) {
            case "", "NATIVE", "RAW", "ASIS" -> NATIVE;
            case "M5", "5M", "5MIN", "5MINUTE" -> M5;
            case "M15", "15M", "15MIN", "15MINUTE" -> M15;
            case "M30", "30M", "30MIN", "30MINUTE", "HALFHOUR" -> M30;
            case "H1", "1H", "1HOUR", "HOURLY", "60MIN" -> H1;
            case "H2", "2H", "2HOUR" -> H2;
            case "H3", "3H", "3HOUR" -> H3;
            case "H4", "4H", "4HOUR" -> H4;
            case "H6", "6H", "6HOUR" -> H6;
            case "H8", "8H", "8HOUR" -> H8;
            case "H12", "12H", "12HOUR" -> H12;
            case "D1", "1D", "1DAY", "DAILY", "DAY" -> D1;
            case "W1", "1W", "1WEEK", "WEEKLY", "WEEK" -> W1;
            case "MN", "1MN", "1MONTH", "MONTHLY", "MONTH", "1MO" -> MN;
            case "Q1", "1Q", "1QUARTER", "QUARTERLY", "QUARTER" -> Q1;
            case "Y1", "1Y", "1YEAR", "YEARLY", "YEAR", "ANNUAL", "ANNUALLY" -> Y1;
            default -> NATIVE;
        };
    }

    public boolean isNative() { return this == NATIVE; }

    /** True when the caller left the timeframe unset / asked for "auto" (per-strategy default). */
    public static boolean isAuto(String s) {
        if (s == null || s.isBlank()) return true;
        String k = s.trim().toUpperCase();
        return k.equals("AUTO") || k.equals("DEFAULT") || k.equals("PER_STRATEGY");
    }

    /** {@link #from(String)}, but an unset / "auto" value yields {@code fallback} instead of NATIVE. */
    public static Timeframe resolve(String s, Timeframe fallback) {
        return isAuto(s) ? fallback : from(s);
    }
}
