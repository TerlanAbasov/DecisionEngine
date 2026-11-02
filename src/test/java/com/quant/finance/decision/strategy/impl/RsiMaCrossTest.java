package com.quant.finance.decision.strategy.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.quant.finance.decision.strategy.BarSeries;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The RSI-versus-its-own-MA cross, and the band guards that decide which crosses are allowed to open a position. */
class RsiMaCrossTest {

    private final RsiMaCross strategy = new RsiMaCross();

    /**
     * {@code up} bars of upward drift then {@code down} bars of downward drift, each with a swing riding on top —
     * a straight ramp would have no down-closes at all, pinning RSI at 100 so it could never cross its own MA.
     */
    private static BarSeries trendThenReverse(int up, int down) {
        int length = up + down;
        Instant[] dates = new Instant[length];
        double[] open = new double[length];
        double[] high = new double[length];
        double[] low = new double[length];
        double[] close = new double[length];
        double[] volume = new double[length];
        for (int i = 0; i < length; i++) {
            double drift = i < up ? 0.5 * i : 0.5 * up - 0.5 * (i - up);
            double px = 100 + drift + 5 * Math.sin(i / 2.0);
            dates[i] = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i * 900L);
            open[i] = px;
            high[i] = px + 0.5;
            low[i] = px - 0.5;
            close[i] = px;
            volume[i] = 1000;
        }
        return new BarSeries("TEST", dates, open, high, low, close, volume);
    }

    private static Map<String, Double> params(String key, double value) {
        Map<String, Double> values = new HashMap<>(new RsiMaCross().defaultParams());
        values.put(key, value);
        return values;
    }

    private static boolean has(double[] sig, double want) {
        for (double v : sig) {
            if (v == want) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aTrendAndItsReversalProduceALongThenAShort() {
        double[] sig = strategy.generateSignals(trendThenReverse(80, 80), null);

        int firstLong = -1;
        int firstShortAfter = -1;
        for (int i = 0; i < sig.length; i++) {
            if (firstLong < 0 && sig[i] == 1) {
                firstLong = i;
            }
            if (firstLong >= 0 && i > firstLong && sig[i] == -1) {
                firstShortAfter = i;
                break;
            }
        }
        assertTrue(firstLong >= 0, "never went long while price rose");
        assertTrue(firstShortAfter > firstLong, "never turned short after the reversal");
    }

    @Test
    void nothingIsTradedUntilTheRsiAndItsMaBothExist() {
        double[] sig = strategy.generateSignals(trendThenReverse(80, 80), null);
        // RSI(14) needs 14 bars and its MA another 14 on top, so no position can open before then
        for (int i = 0; i < 27; i++) {
            assertEquals(0, sig[i], "traded at bar " + i + ", before the MA of the RSI exists");
        }
    }

    @Test
    void anUnreachableOverboughtGuardBlocksEveryLong() {
        // a long needs RSI below `upper`; with upper at 1 no cross can ever qualify
        double[] sig = strategy.generateSignals(trendThenReverse(80, 80), params("upper", 1.0));
        assertFalse(has(sig, 1), "opened a long despite the overbought guard");
        assertTrue(has(sig, -1), "the guard should only block longs, not shorts");
    }

    @Test
    void anUnreachableOversoldGuardBlocksEveryShort() {
        double[] sig = strategy.generateSignals(trendThenReverse(80, 80), params("lower", 99.0));
        assertFalse(has(sig, -1), "opened a short despite the oversold guard");
        assertTrue(has(sig, 1), "the guard should only block shorts, not longs");
    }

    @Test
    void aCrossAgainstThePositionStillClosesItEvenWhenTheOppositeEntryIsBlocked() {
        // shorts are blocked, so after the reversal the long must still be closed rather than held
        double[] sig = strategy.generateSignals(trendThenReverse(80, 80), params("lower", 99.0));
        assertEquals(0, sig[sig.length - 1], "held a long through the whole downtrend");
    }
}
