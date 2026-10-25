package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.engine.Timeframe;
import java.time.Instant;

/** How long a bar of a timeframe lasts; calendar frames (week, month, …) use their typical length, which is close enough for the timing rules built on it. */
final class BarClock {
    private BarClock() {}

    private static final long DAY = 86_400;

    static long barSeconds(Timeframe frame) {
        if (frame.seconds > 0) {
            return frame.seconds;
        }
        return switch (frame) {
            case W1 -> 7 * DAY;
            case MN -> 31 * DAY;
            case Q1 -> 92 * DAY;
            case Y1 -> 366 * DAY;
            default -> throw new IllegalArgumentException("A bar of " + frame + " has no length");
        };
    }

    /** When the bar that opened at {@code barOpen} is complete. */
    static Instant closesAt(Instant barOpen, Timeframe frame) {
        return barOpen.plusSeconds(barSeconds(frame));
    }
}
