package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.autotrade.AutoTradeSettings.OrderType;
import com.quant.finance.decision.autotrade.AutoTradeSettings.TimeInForce;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AutoTradeSettingsTest {

    private static final Set<String> KNOWN = Set.of("rsi", "macd");
    private static final Instant SINCE = Instant.parse("2026-09-21T14:00:00Z");

    private static AutoTradeSettings with(double quantity, OrderType type, TimeInForce tif, List<String> names, List<String> symbols, int max) {
        return new AutoTradeSettings(false, null, quantity, type, tif, names, symbols, max);
    }

    private static AutoTradeSettings valid() { return with(5, OrderType.MKT, TimeInForce.DAY, List.of(), List.of(), 25); }

    @Test
    void defaultsAreOffAndSmall() {
        AutoTradeSettings d = AutoTradeSettings.defaults();
        assertFalse(d.enabled());
        assertNull(d.enabledSince());
        assertEquals(1, d.quantity());
        assertDoesNotThrow(() -> d.validated(KNOWN::contains));
    }

    @Test
    void namesAndSymbolsAreNormalisedWithoutBlanksOrRepeats() {
        AutoTradeSettings v = with(5, OrderType.LMT, TimeInForce.GTC, Arrays.asList(" rsi ", "rsi", null, "", "macd"),
                Arrays.asList(" aapl", "AAPL", "brk.b", null), 10).validated(KNOWN::contains);
        assertEquals(List.of("rsi", "macd"), v.strategyNames());
        assertEquals(List.of("AAPL", "BRK.B"), v.symbols());
        assertNull(with(5, OrderType.MKT, TimeInForce.DAY, null, null, 10).validated(KNOWN::contains).strategyNames().stream().findAny().orElse(null));
    }

    @Test
    void badValuesAreRejectedWithTheFieldNamed() {
        for (double q : new double[]{0, -1, Double.NaN, 1_000_001})
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> with(q, OrderType.MKT, TimeInForce.DAY, List.of(), List.of(), 25).validated(KNOWN::contains)).getMessage().contains("quantity"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> with(1, null, TimeInForce.DAY, List.of(), List.of(), 25).validated(KNOWN::contains))
                .getMessage().contains("orderType"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> with(1, OrderType.MKT, null, List.of(), List.of(), 25).validated(KNOWN::contains))
                .getMessage().contains("tif"));
        assertThrows(IllegalArgumentException.class, () -> with(1, OrderType.MKT, TimeInForce.DAY, List.of(), List.of(), 0).validated(KNOWN::contains));
        assertThrows(IllegalArgumentException.class, () -> with(1, OrderType.MKT, TimeInForce.DAY, List.of(), List.of(), 501).validated(KNOWN::contains));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> with(1, OrderType.MKT, TimeInForce.DAY, List.of("nope"), List.of(), 25).validated(KNOWN::contains))
                .getMessage().contains("nope"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> with(1, OrderType.MKT, TimeInForce.DAY, List.of(), List.of("bad symbol!"), 25).validated(KNOWN::contains))
                .getMessage().contains("Invalid symbol"));
    }

    @Test
    void switchingOnRecordsWhenAndSwitchingOffForgetsIt() {
        AutoTradeSettings on = valid().switchedOn(true, SINCE);
        assertTrue(on.enabled());
        assertEquals(SINCE, on.enabledSince());
        assertEquals(SINCE, on.switchedOn(true, SINCE.plusSeconds(600)).enabledSince(), "already on: the original time stands");
        AutoTradeSettings off = on.switchedOn(false, SINCE.plusSeconds(600));
        assertFalse(off.enabled());
        assertNull(off.enabledSince());
    }

    @Test
    void editingTheFormNeverTouchesTheSwitch() {
        AutoTradeSettings on = valid().switchedOn(true, SINCE);
        AutoTradeSettings edited = new AutoTradeSettings(false, null, 7, OrderType.LMT, TimeInForce.GTC, List.of("rsi"), List.of("AAPL"), 3);
        AutoTradeSettings result = on.editedTo(edited);
        assertTrue(result.enabled());
        assertEquals(SINCE, result.enabledSince());
        assertEquals(7, result.quantity());
        assertEquals(OrderType.LMT, result.orderType());
        assertEquals(3, result.maxCommandsPerRun());
    }
}
