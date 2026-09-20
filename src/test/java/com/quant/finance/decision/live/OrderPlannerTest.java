package com.quant.finance.decision.live;

import com.quant.finance.decision.live.OrderPlanner.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OrderPlannerTest {

    private static Plan plan(Map<String, Double> virt, Map<String, Double> actual, Map<String, Double> px, double cap, int max) {
        return OrderPlanner.plan(virt, actual, px, cap, max);
    }

    private static SymbolPlan of(Plan p, String sym) {
        return p.symbols().stream().filter(s -> s.symbol().equals(sym)).findFirst().orElseThrow();
    }

    @Test
    void sharesRoundToTheNearestWholeShareTiesAwayFromZero() {
        assertEquals(3, OrderPlanner.wholeShares(2.5));
        assertEquals(-3, OrderPlanner.wholeShares(-2.5));
        assertEquals(0, OrderPlanner.wholeShares(0.49));
        assertEquals(1, OrderPlanner.wholeShares(0.5));
        assertEquals(0, OrderPlanner.wholeShares(-0.4));
        assertEquals(-12, OrderPlanner.wholeShares(-12.4));
    }

    @Test
    void theOrderIsTheGapBetweenTheWantedAndTheHeldPosition() {
        Plan p = plan(Map.of("A", 12.4, "B", 12.4, "C", -3.0), Map.of("B", 12.0, "C", -1.0),
                Map.of("A", 10.0, "B", 10.0, "C", 10.0), 0, 0);
        SymbolPlan a = of(p, "A");
        assertEquals(List.of(new Order("A", "buy", 12, false)), a.orders());
        assertEquals(12, a.targetQty());
        assertTrue(of(p, "B").orders().isEmpty(), "already holding the wanted 12 shares");
        assertEquals(List.of(new Order("C", "sell", 2, false)), of(p, "C").orders());
        assertEquals(2, p.withOrders().size());
    }

    @Test
    void reducingOrClosingIsMarkedAsReducingExposure() {
        Plan p = plan(Map.of("A", 0.2, "B", 5.0), Map.of("A", 5.0, "B", 9.0), Map.of("A", 10.0, "B", 10.0), 0, 0);
        assertEquals(List.of(new Order("A", "sell", 5, true)), of(p, "A").orders());
        assertEquals(List.of(new Order("B", "sell", 4, true)), of(p, "B").orders());
    }

    @Test
    void movingThroughZeroIsTwoOrdersCloseThenOpen() {
        Plan p = plan(Map.of("A", -3.6, "B", 4.0), Map.of("A", 2.0, "B", -1.0), Map.of("A", 10.0, "B", 10.0), 0, 0);
        assertEquals(List.of(new Order("A", "sell", 2, true), new Order("A", "sell", 4, false)), of(p, "A").orders());
        assertEquals(List.of(new Order("B", "buy", 1, true), new Order("B", "buy", 4, false)), of(p, "B").orders());
    }

    @Test
    void aSymbolWithoutAPriceIsNotPlanned() {
        Plan p = plan(Map.of("A", 5.0, "B", 5.0), Map.of(), Map.of("A", 10.0), 0, 0);
        assertEquals(1, p.symbols().size());
        assertEquals("A", p.symbols().get(0).symbol());
    }

    @Test
    void theExposureCapHoldsBackTheBiggestOrderThatAddsExposureUntilItFits() {
        Plan p = plan(Map.of("A", 10.0, "B", 20.0, "C", 5.0), Map.of(), Map.of("A", 100.0, "B", 100.0, "C", 100.0), 2500, 0);
        assertEquals(List.of(new Skipped("B", OrderPlanner.EXPOSURE_CAP)), p.skipped());
        assertTrue(of(p, "B").orders().isEmpty());
        assertFalse(of(p, "A").orders().isEmpty());
        assertFalse(of(p, "C").orders().isEmpty());
    }

    @Test
    void theExposureCapNeverBlocksAnOrderThatReducesExposure() {
        Plan p = plan(Map.of("A", 10.0), Map.of("A", 30.0), Map.of("A", 100.0), 1000, 0);   // already $3000, cap $1000
        assertTrue(p.skipped().isEmpty());
        assertEquals(List.of(new Order("A", "sell", 20, true)), of(p, "A").orders());
    }

    @Test
    void theOrderLimitKeepsReducingOrdersFirstThenTheLargest() {
        Plan p = plan(Map.of("A", 50.0, "B", 10.0, "C", 0.0),
                Map.of("C", 8.0), Map.of("A", 10.0, "B", 10.0, "C", 10.0), 0, 2);
        assertEquals(List.of("A", "C"), p.withOrders().stream().map(SymbolPlan::symbol).sorted().toList(),
                "the reducing order (C) and the largest opening order (A) are kept");
        assertEquals(List.of(new Skipped("B", OrderPlanner.ORDER_LIMIT)), p.skipped());
    }

    @Test
    void theOrderLimitCountsBothLegsOfAMoveThroughZero() {
        Plan p = plan(Map.of("A", -5.0), Map.of("A", 5.0), Map.of("A", 10.0), 0, 1);
        assertTrue(p.withOrders().isEmpty());
        assertEquals(List.of(new Skipped("A", OrderPlanner.ORDER_LIMIT)), p.skipped());
    }
}
