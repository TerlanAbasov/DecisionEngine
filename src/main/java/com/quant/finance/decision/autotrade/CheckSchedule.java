package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.engine.Timeframe;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When a symbol's bars of a timeframe are next worth fetching: once the bar after the last one seen is complete, so a 15-minute strategy is looked at every 15 minutes, not every tick.
 * Just after that moment it looks again quickly (data may be late); once clearly overdue (market closed) only now and then.
 */
final class CheckSchedule {

    /** Data providers publish a bar a little after it closes. */
    static final Duration GRACE = Duration.ofSeconds(20);
    static final Duration LATE_WINDOW = Duration.ofMinutes(5);
    static final Duration LATE_RETRY = Duration.ofSeconds(30);
    static final Duration IDLE_RETRY_CAP = Duration.ofMinutes(15);

    private final Map<String, Instant> nextCheck = new ConcurrentHashMap<>();

    boolean isDue(String symbol, Timeframe timeframe, Instant now) {
        Instant next = nextCheck.get(key(symbol, timeframe));
        return next == null || !now.isBefore(next);
    }

    /** Notes that the bars were looked at ({@code lastBarOpen} is the last completed one, null when there was nothing to evaluate). */
    void checked(String symbol, Timeframe timeframe, Instant lastBarOpen, Instant now) {
        nextCheck.put(key(symbol, timeframe), next(now, lastBarOpen, timeframe));
    }

    static Instant next(Instant now, Instant lastBarOpen, Timeframe timeframe) {
        long bar = BarClock.barSeconds(timeframe);
        Duration idle = Duration.ofSeconds(Math.min(bar, IDLE_RETRY_CAP.toSeconds()));
        if (lastBarOpen == null) return now.plus(idle);

        Instant due = lastBarOpen.plusSeconds(2 * bar).plus(GRACE);          // the next bar is complete, and its data has landed
        if (now.isBefore(due)) return due;
        return now.plus(Duration.between(due, now).compareTo(LATE_WINDOW) < 0 ? LATE_RETRY : idle);
    }

    private static String key(String symbol, Timeframe timeframe) {
        return symbol + "|" + timeframe.name();
    }
}
