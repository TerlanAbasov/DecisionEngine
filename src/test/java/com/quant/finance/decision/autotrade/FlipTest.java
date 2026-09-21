package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.autotrade.TradeCommand.Side;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.live.SignalEvaluator.Reading;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FlipTest {

    private static final Instant BAR_OPEN = Instant.parse("2026-09-21T14:45:00Z");           // an M15 bar, complete at 15:00
    private static final Instant ENABLED = Instant.parse("2026-09-21T14:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-21T15:00:30Z");

    private static Optional<Flip> detect(double signal, double previous, Instant enabledSince, Instant now) {
        return Flip.detect("rsi", "AAPL", Timeframe.M15, new Reading(BAR_OPEN, 101.5, signal, previous), enabledSince, now);
    }

    @Test
    void turningLongIsABuyAndTurningShortIsASell() {
        Flip buy = detect(1, 0, ENABLED, NOW).orElseThrow();
        assertEquals(Side.BUY, buy.side());
        assertEquals("AAPL", buy.symbol());
        assertEquals(BAR_OPEN, buy.barOpen());
        assertEquals(101.5, buy.close());
        assertEquals(Side.SELL, detect(-0.4, 0, ENABLED, NOW).orElseThrow().side());
    }

    @Test
    void reversingIsAFlipInTheNewDirection() {
        assertEquals(Side.SELL, detect(-1, 1, ENABLED, NOW).orElseThrow().side());
        assertEquals(Side.BUY, detect(1, -1, ENABLED, NOW).orElseThrow().side());
    }

    @Test
    void aStandingPositionIsNotAFlipEvenWhenItsSizeChanges() {
        assertTrue(detect(1, 1, ENABLED, NOW).isEmpty());
        assertTrue(detect(0.3, 1, ENABLED, NOW).isEmpty());
        assertTrue(detect(-1, -0.5, ENABLED, NOW).isEmpty());
    }

    @Test
    void goingFlatSendsNothingBecauseThereIsNoCloseCommand() {
        assertTrue(detect(0, 1, ENABLED, NOW).isEmpty());
        assertTrue(detect(0, -1, ENABLED, NOW).isEmpty());
        assertTrue(detect(0, 0, ENABLED, NOW).isEmpty());
    }

    @Test
    void aBarThatCompletedBeforeTheJobWasSwitchedOnNeverTriggersAnything() {
        assertTrue(detect(1, 0, Instant.parse("2026-09-21T15:00:00Z"), NOW).isEmpty(), "closed exactly when it was switched on");
        assertTrue(detect(1, 0, Instant.parse("2026-09-21T15:00:10Z"), NOW).isEmpty());
        assertTrue(detect(1, 0, null, NOW).isEmpty(), "no switch-on time: fail safe");
    }

    @Test
    void aSignalThatIsTwoBarsOldOrMoreIsStaleAndDropped() {
        assertTrue(detect(1, 0, ENABLED, Instant.parse("2026-09-21T15:30:00Z")).isPresent(), "exactly two bars after it closed is still fresh");
        assertTrue(detect(1, 0, ENABLED, Instant.parse("2026-09-21T15:30:01Z")).isEmpty());
        assertTrue(detect(1, 0, ENABLED, Instant.parse("2026-09-24T09:00:00Z")).isEmpty(), "found after a long outage");
    }

    @Test
    void calendarFramesUseTheirTypicalLength() {
        Instant weekOpen = Instant.parse("2026-09-14T00:00:00Z");
        Reading weekly = new Reading(weekOpen, 50, 1, 0);
        assertTrue(Flip.detect("s", "AAPL", Timeframe.W1, weekly, Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-21T06:00:00Z")).isPresent());
        assertTrue(Flip.detect("s", "AAPL", Timeframe.W1, weekly, Instant.parse("2026-09-21T03:00:00Z"), Instant.parse("2026-09-21T06:00:00Z")).isEmpty());
    }
}
