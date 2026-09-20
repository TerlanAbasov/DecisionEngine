package com.quant.finance.decision.live;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class LiveSettingsTest {
    private static final Predicate<String> KNOWN = n -> !n.equals("nope");

    private static LiveSettings with(java.util.function.UnaryOperator<LiveSettings> f) { return f.apply(LiveSettings.defaults()); }

    private static LiveSettings s(int interval, double alloc, double size, int lookback, double gross, int orders, int fill,
                                  String tf, List<String> names, List<String> syms) {
        return new LiveSettings(true, true, interval, alloc, size, true, tf, lookback, names, syms, gross, orders, true, fill, true);
    }

    @Test
    void theDefaultsAreOffAndADryRunAndAreThemselvesValid() {
        LiveSettings d = LiveSettings.defaults();
        assertFalse(d.enabled());
        assertTrue(d.dryRun(), "nothing is sent to the broker until the dry run is switched off too");
        assertEquals(d, d.validated(KNOWN));
    }

    @Test
    void namesAreTrimmedUppercasedAndDeduplicated() {
        LiveSettings v = s(60, 100, 1, 100, 1000, 10, 30, "m15", List.of(" a ", "a", "b", ""), List.of(" mu ", "MU", "brk.b")).validated(n -> true);
        assertEquals(List.of("a", "b"), v.strategyNames());
        assertEquals(List.of("MU", "BRK.B"), v.symbols());
        assertEquals("M15", v.timeframeMode());
        assertEquals("AUTO", s(60, 100, 1, 100, 1000, 10, 30, "auto", List.of(), List.of()).validated(KNOWN).timeframeMode());
        assertEquals("AUTO", s(60, 100, 1, 100, 1000, 10, 30, null, List.of(), List.of()).validated(KNOWN).timeframeMode());
    }

    @Test
    void outOfRangeValuesAreRejectedNamingTheField() {
        record C(LiveSettings s, String field) {}
        for (C c : List.of(
                new C(s(29, 100, 1, 100, 1000, 10, 30, "AUTO", List.of(), List.of()), "intervalSeconds"),
                new C(s(60, 0.5, 1, 100, 1000, 10, 30, "AUTO", List.of(), List.of()), "allocationUsd"),
                new C(s(60, 100, 9, 100, 1000, 10, 30, "AUTO", List.of(), List.of()), "positionSize"),
                new C(s(60, 100, 1, 10, 1000, 10, 30, "AUTO", List.of(), List.of()), "lookbackBars"),
                new C(s(60, 100, 1, 100, 10, 10, 30, "AUTO", List.of(), List.of()), "maxGrossUsd"),
                new C(s(60, 100, 1, 100, 1000, 0, 30, "AUTO", List.of(), List.of()), "maxOrdersPerCycle"),
                new C(s(60, 100, 1, 100, 1000, 10, 2, "AUTO", List.of(), List.of()), "fillTimeoutSeconds"),
                new C(s(60, Double.NaN, 1, 100, 1000, 10, 30, "AUTO", List.of(), List.of()), "allocationUsd"))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> c.s().validated(KNOWN), c.field());
            assertTrue(e.getMessage().startsWith(c.field()), e.getMessage());
        }
    }

    @Test
    void anUnknownStrategyAnInvalidSymbolOrNativeTimeframeIsRejected() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> s(60, 100, 1, 100, 1000, 10, 30, "AUTO", List.of("ok", "nope"), List.of()).validated(KNOWN)).getMessage().contains("nope"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> s(60, 100, 1, 100, 1000, 10, 30, "AUTO", List.of(), List.of("MU", "bad symbol!")).validated(KNOWN)).getMessage().contains("Invalid symbol"));
        assertThrows(IllegalArgumentException.class,
                () -> s(60, 100, 1, 100, 1000, 10, 30, "NATIVE", List.of(), List.of()).validated(KNOWN));
        assertEquals("H1", s(60, 100, 1, 100, 1000, 10, 30, "1h", List.of(), List.of()).validated(KNOWN).timeframeMode());
    }
}
