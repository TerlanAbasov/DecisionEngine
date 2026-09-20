package com.quant.finance.decision.live;

import com.quant.finance.decision.live.SlotLedger.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SlotLedgerTest {
    private static final Instant T0 = Instant.parse("2026-09-21T14:00:00Z");
    private static final Instant T1 = T0.plusSeconds(300);
    private static final Params P = new Params(1000, 1, true, 0, 0);

    private static Transition open(double signal, double px, Params p) {
        return SlotLedger.apply(State.FLAT, signal, px, px, T0, p, null);
    }

    @Test
    void aLongSignalOpensASlotSizedByTheAllocationAndPrice() {
        Transition t = open(1, 100, P);
        assertEquals(1, t.next().direction());
        assertEquals(10, t.next().qty(), 1e-9);
        assertEquals(100, t.next().entryPrice());
        assertEquals(T0, t.next().entryTime());
        assertEquals(10, t.qtyDelta(), 1e-9);
        assertTrue(t.traded());
        assertTrue(t.closed().isEmpty());
    }

    @Test
    void aShortSignalOpensAShortWhenAllowedAndNothingWhenNot() {
        Transition s = open(-1, 100, P);
        assertEquals(-1, s.next().direction());
        assertEquals(-10, s.qtyDelta(), 1e-9);
        Transition none = open(-1, 100, new Params(1000, 1, false, 0, 0));
        assertEquals(0, none.next().direction());
        assertFalse(none.traded());
        assertEquals(0, none.qtyDelta());
    }

    @Test
    void theSignalMagnitudeAndPositionSizeScaleTheShareCount() {
        assertEquals(5, open(0.5, 100, P).next().qty(), 1e-9);
        assertEquals(20, open(1, 100, new Params(1000, 2, true, 0, 0)).next().qty(), 1e-9);
        assertEquals(10, open(3.0, 100, P).next().qty(), 1e-9, "a signal beyond 1 is capped at full size");
        assertEquals(0, open(Double.NaN, 100, P).next().direction());
    }

    @Test
    void holdingTheSameSignalChangesNothing() {
        State held = open(1, 100, P).next();
        Transition t = SlotLedger.apply(held, 1, 105, 105, T1, P, null);
        assertEquals(held, t.next());
        assertFalse(t.traded());
        assertEquals(0, t.qtyDelta());
        assertTrue(t.closed().isEmpty());
    }

    @Test
    void closingBooksThePnlOfALongAndOfAShort() {
        State lng = open(1, 100, P).next();
        Transition c = SlotLedger.apply(lng, 0, 110, 110, T1, P, null);
        assertEquals(0, c.next().direction());
        assertEquals(1, c.closed().size());
        ClosedTrade t = c.closed().get(0);
        assertEquals("LONG", t.side());
        assertEquals(100, t.pnlUsd(), 1e-9);
        assertEquals(10, t.returnPct(), 1e-9);
        assertEquals(SlotLedger.SIGNAL, t.reason());
        assertEquals(100, c.next().realizedPnl(), 1e-9);
        assertEquals(-10, c.qtyDelta(), 1e-9);

        State sht = open(-1, 100, P).next();
        ClosedTrade s = SlotLedger.apply(sht, 0, 90, 90, T1, P, null).closed().get(0);
        assertEquals("SHORT", s.side());
        assertEquals(100, s.pnlUsd(), 1e-9, "a short profits when the price falls");
        assertEquals(10, s.returnPct(), 1e-9);
    }

    @Test
    void aFlipClosesOneTradeAndOpensTheOtherSideInOneStep() {
        State lng = open(1, 100, P).next();
        Transition t = SlotLedger.apply(lng, -1, 110, 110, T1, P, null);
        assertEquals(1, t.closed().size());
        assertEquals(-1, t.next().direction());
        assertEquals(9.0909, t.next().qty(), 1e-3);
        assertEquals(110, t.next().entryPrice());
        assertEquals(t.next().signedQty() - lng.signedQty(), t.qtyDelta(), 1e-9);
        assertEquals(100, t.next().realizedPnl(), 1e-9);
    }

    @Test
    void sharesComeFromTheDecisionPriceButTheFillIsRecordedAtTheExecutionPrice() {
        Transition o = SlotLedger.apply(State.FLAT, 1, 100, 101, T0, P, null);
        assertEquals(10, o.next().qty(), 1e-9);
        assertEquals(101, o.next().entryPrice());
        ClosedTrade t = SlotLedger.apply(o.next(), 0, 110, 109, T1, P, null).closed().get(0);
        assertEquals(109, t.exitPrice());
        assertEquals(80, t.pnlUsd(), 1e-9);
    }

    @Test
    void aBigChangeInWantedSizeIsARestartAndASmallOneIsIgnored() {
        State held = open(1, 100, P).next();
        Transition small = SlotLedger.apply(held, 0.9, 100, 100, T1, P, null);   // 10% smaller: within tolerance
        assertEquals(held, small.next());
        assertFalse(small.traded());
        Transition big = SlotLedger.apply(held, 0.5, 100, 100, T1, P, null);
        assertEquals(1, big.closed().size());
        assertEquals(SlotLedger.RESIZE, big.closed().get(0).reason());
        assertEquals(5, big.next().qty(), 1e-9);
        assertEquals(-5, big.qtyDelta(), 1e-9);
    }

    @Test
    void aStopLossClosesTheTradeAndKeepsItClosedUntilTheSignalChanges() {
        Params sl = new Params(1000, 1, true, 5, 0);
        State held = open(1, 100, sl).next();
        Transition stop = SlotLedger.apply(held, 1, 94, 94, T1, sl, null);
        assertEquals(0, stop.next().direction());
        assertEquals(SlotLedger.STOP_LOSS, stop.closed().get(0).reason());
        assertEquals(-60, stop.closed().get(0).pnlUsd(), 1e-9);
        assertEquals(1, stop.next().blockedDir());

        Transition still = SlotLedger.apply(stop.next(), 1, 100, 100, T1.plusSeconds(300), sl, null);
        assertEquals(0, still.next().direction(), "still asking for the stopped-out direction: stay out");
        assertFalse(still.traded());

        Transition flat = SlotLedger.apply(still.next(), 0, 100, 100, T1.plusSeconds(600), sl, null);
        assertEquals(0, flat.next().blockedDir(), "the strategy moved on: entry is armed again");
        Transition again = SlotLedger.apply(flat.next(), 1, 100, 100, T1.plusSeconds(900), sl, null);
        assertEquals(1, again.next().direction());
    }

    @Test
    void aTakeProfitAndAShortStopWork() {
        Params tp = new Params(1000, 1, true, 0, 10);
        State held = open(1, 100, tp).next();
        assertEquals(SlotLedger.TAKE_PROFIT, SlotLedger.apply(held, 1, 111, 111, T1, tp, null).closed().get(0).reason());
        assertEquals(1, SlotLedger.apply(held, 1, 109, 109, T1, tp, null).next().direction(), "not yet");

        Params sl = new Params(1000, 1, true, 5, 0);
        State sht = open(-1, 100, sl).next();
        Transition stop = SlotLedger.apply(sht, -1, 106, 106, T1, sl, null);
        assertEquals(SlotLedger.STOP_LOSS, stop.closed().get(0).reason());
        assertEquals(-1, stop.next().blockedDir());
    }

    @Test
    void aForcedReasonClosesRegardlessOfTheSignalAndClearsTheBlock() {
        State held = open(1, 100, P).next();
        Transition t = SlotLedger.apply(held, 1, 105, 105, T1, P, SlotLedger.FLATTEN);
        assertEquals(0, t.next().direction());
        assertEquals(SlotLedger.FLATTEN, t.closed().get(0).reason());
        State blocked = new State(0, 0, 0, null, 1, 0);
        assertEquals(0, SlotLedger.apply(blocked, 0, 100, 100, T1, P, SlotLedger.REMOVED).next().blockedDir());
    }

    @Test
    void anUnusablePriceOrAZeroAllocationChangesNothing() {
        State held = open(1, 100, P).next();
        for (double bad : new double[] {0, -1, Double.NaN}) {
            Transition t = SlotLedger.apply(held, 0, bad, bad, T1, P, null);
            assertEquals(held, t.next());
            assertFalse(t.traded());
        }
        assertFalse(open(1, 100, new Params(0, 1, true, 0, 0)).traded());
    }

    @Test
    void unrealizedIsTheOpenProfitOrLoss() {
        State lng = open(1, 100, P).next(), sht = open(-1, 100, P).next();
        assertEquals(50, SlotLedger.unrealized(lng, 105), 1e-9);
        assertEquals(-50, SlotLedger.unrealized(sht, 105), 1e-9);
        assertEquals(0, SlotLedger.unrealized(State.FLAT, 105));
    }

    @Test
    void realizedPnlAccumulatesAcrossTrades() {
        State s = open(1, 100, P).next();
        s = SlotLedger.apply(s, 0, 110, 110, T1, P, null).next();                      // +100
        s = SlotLedger.apply(s, -1, 110, 110, T1.plusSeconds(60), P, null).next();     // short 9.09 @110
        s = SlotLedger.apply(s, 0, 100, 100, T1.plusSeconds(120), P, null).next();     // +90.9
        assertEquals(190.909, s.realizedPnl(), 1e-2);
    }
}
