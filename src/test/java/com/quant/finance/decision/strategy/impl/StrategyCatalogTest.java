package com.quant.finance.decision.strategy.impl;

import com.quant.finance.decision.data.SyntheticData;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Runs every catalog strategy against realistic synthetic bars: no exceptions, no NaN leaking out. */
class StrategyCatalogTest {

    @Test
    void catalogHasUniquelyNamedStrategies() {
        List<TradingStrategy> all = StrategyCatalog.all();
        // no hard-coded size: the catalog is curated over time (pruned/extended), so pinning a
        // count just makes this test fail on every legitimate change without catching a bug
        assertFalse(all.isEmpty(), "catalog is empty");
        Set<String> names = new HashSet<>();
        for (TradingStrategy s : all) assertTrue(names.add(s.name()), "duplicate name: " + s.name());
    }

    @Test
    void everyStrategyGeneratesCleanSignalsOnSyntheticData() {
        BarSeries bars = SyntheticData.generate("TEST", 6.5, LocalDate.now());
        for (TradingStrategy s : StrategyCatalog.all()) {
            double[] sig = assertDoesNotThrow(() -> s.generateSignals(bars, null),
                    () -> "strategy '" + s.name() + "' threw");
            assertEquals(bars.size(), sig.length, "strategy '" + s.name() + "' wrong signal length");
            boolean longOnly = "long_only".equals(s.direction());
            for (int i = 0; i < sig.length; i++) {
                double v = sig[i];
                assertFalse(Double.isNaN(v), s.name() + " produced NaN at bar " + i);
                assertTrue(v >= -1.0001 && v <= 1.0001, s.name() + " out of [-1,1] at bar " + i + ": " + v);
                if (longOnly) assertTrue(v >= 0, s.name() + " (long_only) went short at bar " + i);
            }
        }
    }

    @Test
    void everyStrategyHandlesAShortSeriesWithoutThrowing() {
        BarSeries bars = SyntheticData.generate("SHORT", 0.5, LocalDate.now());
        for (TradingStrategy s : StrategyCatalog.all()) {
            assertDoesNotThrow(() -> s.generateSignals(bars, null), () -> "strategy '" + s.name() + "' threw on a short series");
        }
    }
}
