package com.quant.finance.decision.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.finance.decision.entity.TradeEntity;
import com.quant.finance.decision.dto.Dtos.TradeDetailDto;
import com.quant.finance.decision.dto.Dtos.TradePageDto;
import com.quant.finance.decision.engine.SymbolResult;
import com.quant.finance.decision.service.TradeDetails.RunCosts;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TradeDetailsTest {

    private static final RunCosts COSTS = new RunCosts(100_000, 10, 20, 1.0);   // 30 bps a side

    private static TradeEntity trade(long id, String symbol, String side, double entry, double exit) {
        TradeEntity t = new TradeEntity();
        t.setId(id);
        t.setSymbol(symbol);
        t.setSide(side);
        t.setEntryDate(Instant.parse("2026-03-01T14:30:00Z").plusSeconds(id * 900));
        t.setExitDate(Instant.parse("2026-03-01T14:30:00Z").plusSeconds(id * 900 + 1800));
        t.setEntryPx(entry);
        t.setExitPx(exit);
        t.setBars(2);
        t.setReturnPct(0);
        return t;
    }

    /** A trade carrying the engine's exact accounting (as rows saved after this feature do). */
    private static TradeEntity stored(long id, String symbol, String side, double gross, double cost, double exposure) {
        TradeEntity t = trade(id, symbol, side, 100, 101);
        t.setGrossReturn(gross);
        t.setCost(cost);
        t.setNetReturn(gross - cost);
        t.setExposure(exposure);
        t.setStillOpen(false);
        t.setReturnPct((gross - cost) / 3);
        return t;
    }

    @Test
    void storedTradesUseTheEnginesExactAccountingAndMoneyAddsUp() {
        TradeDetailDto d = TradeDetails.toDto(stored(1, "MU", "LONG", 0.0200, 0.0060, 1.0), COSTS);
        assertEquals(2.0, d.grossPct(), 1e-9);
        assertEquals(0.6, d.commissionPct() + d.slippagePct(), 1e-9);
        assertEquals(0.2, d.commissionPct(), 1e-9, "10 of the 30 bps are commission");
        assertEquals(0.4, d.slippagePct(), 1e-9);
        assertEquals(1.4, d.netPct(), 1e-9);
        assertEquals(d.grossPct() - d.commissionPct() - d.slippagePct(), d.netPct(), 1e-9);
        assertEquals(2000, d.grossPnl(), 1e-6);
        assertEquals(600, d.commission() + d.slippage(), 1e-6, "0.6% of the 100k capital");
        assertEquals(200, d.commission(), 1e-6);
        assertEquals(400, d.slippage(), 1e-6);
        assertEquals(1400, d.netPnl(), 1e-6);
        assertEquals(d.grossPnl() - d.commission() - d.slippage(), d.netPnl(), 1e-6);
        assertEquals(100_000, d.notional(), 1e-6);
        assertEquals(1000, d.shares(), 1e-6, "notional / entry price");
        assertEquals((2.0 - 0.6) / 3, d.contribPct(), 1e-9, "contribution is the stored weighted return, untouched");
        assertFalse(d.open());
    }

    @Test
    void percentagesAreOnThePositionWhileMoneyIsOnTheCapital() {
        // half-size trade: earned 1% of capital gross, 0.3% cost => 2% / 0.6% on its own notional
        TradeDetailDto d = TradeDetails.toDto(stored(1, "MU", "SHORT", 0.0100, 0.0030, 0.5), COSTS);
        assertEquals(2.0, d.grossPct(), 1e-9);
        assertEquals(1.4, d.netPct(), 1e-9);
        assertEquals(50_000, d.notional(), 1e-6);
        assertEquals(700, d.netPnl(), 1e-6, "0.7% of the 100k capital");
    }

    @Test
    void legacyRowsAreDerivedFromPricesAndTheRunsCostSettings() {
        TradeDetailDto lg = TradeDetails.toDto(trade(1, "MU", "LONG", 100, 102), COSTS);
        assertEquals(2.0, lg.grossPct(), 1e-9);
        assertEquals(0.6, lg.commissionPct() + lg.slippagePct(), 1e-9, "one fill each way");
        assertEquals(1.4, lg.netPct(), 1e-9);
        TradeDetailDto sh = TradeDetails.toDto(trade(2, "MU", "SHORT", 100, 102), COSTS);
        assertEquals(-2.0, sh.grossPct(), 1e-9, "a short loses when the price rises");
        assertEquals(-2.6, sh.netPct(), 1e-9);
        assertEquals(1.0, lg.exposure(), 1e-12, "falls back to the run's position size");
        assertFalse(lg.open());
    }

    @Test
    void zeroCostSettingsGiveZeroCommissionAndSlippageWithoutDividingByZero() {
        TradeDetailDto d = TradeDetails.toDto(trade(1, "MU", "LONG", 100, 101), new RunCosts(100_000, 0, 0, 1.0));
        assertEquals(0, d.commissionPct(), 0);
        assertEquals(0, d.slippagePct(), 0);
        assertEquals(1.0, d.netPct(), 1e-9);
    }

    @Test
    void degeneratePricesDoNotProduceNaNOrInfinity() {
        TradeDetailDto d = TradeDetails.toDto(trade(1, "MU", "LONG", 0, 5), COSTS);
        for (double v : new double[] {d.grossPct(), d.netPct(), d.shares(), d.netPnl()})
            assertTrue(Double.isFinite(v));
    }

    // ---- paging / filtering / sorting -----------------------------------------------------

    private static List<TradeDetailDto> sample() {
        List<TradeDetailDto> all = new ArrayList<>();
        String[] syms = {"MU", "SNDK", "NBIS"};
        for (int i = 1; i <= 30; i++) {
            String side = i % 2 == 0 ? "LONG" : "SHORT";
            // net alternates sign and grows with i, so every ordering is distinguishable
            double gross = (i % 3 == 0 ? -1 : 1) * i * 0.001;
            all.add(TradeDetails.toDto(stored(i, syms[i % 3], side, gross, 0.001, 1.0), COSTS));
        }
        return all;
    }

    @Test
    void pagesAreDisjointCompleteAndStable() {
        List<TradeDetailDto> all = sample();
        List<Long> seen = new ArrayList<>();
        for (int p = 0; p < 4; p++) {
            TradePageDto pg = TradeDetails.page(all, null, null, "netPct", "desc", p, 10);
            assertEquals(30, pg.total());
            pg.items().forEach(t -> seen.add(t.id()));
        }
        assertEquals(30, seen.size());
        assertEquals(30, seen.stream().distinct().count(), "no row may appear on two pages");
        assertEquals(TradeDetails.page(all, null, null, "netPct", "desc", 1, 10).items(),
                TradeDetails.page(all, null, null, "netPct", "desc", 1, 10).items(), "same request, same page");
    }

    @Test
    void sortsAscendingAndDescendingOnADerivedColumn() {
        List<TradeDetailDto> all = sample();
        List<TradeDetailDto> desc = TradeDetails.page(all, null, null, "netPct", "desc", 0, 500).items();
        List<TradeDetailDto> asc = TradeDetails.page(all, null, null, "netPct", "asc", 0, 500).items();
        for (int i = 1; i < desc.size(); i++) assertTrue(desc.get(i - 1).netPct() >= desc.get(i).netPct());
        for (int i = 1; i < asc.size(); i++) assertTrue(asc.get(i - 1).netPct() <= asc.get(i).netPct());
        assertEquals(desc.get(0).id(), asc.get(asc.size() - 1).id());
    }

    @Test
    void defaultsAreNewestFirst() {
        TradePageDto pg = TradeDetails.page(sample(), null, null, null, null, 0, 5);
        assertEquals(30, pg.items().get(0).id());
        assertEquals(29, pg.items().get(1).id());
    }

    @Test
    void selectReturnsEveryMatchingTradeInOrderWithoutPaging() {
        List<TradeDetailDto> all = new ArrayList<>();
        for (long i = 1; i <= 7; i++) all.add(TradeDetails.toDto(trade(i, i % 2 == 0 ? "AAA" : "BBB", "LONG", 100, 100 + i), COSTS));
        List<TradeDetailDto> rows = TradeDetails.select(all, "aaa", null, "netPnl", "asc");
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).netPnl() <= rows.get(1).netPnl() && rows.get(1).netPnl() <= rows.get(2).netPnl());
        assertEquals(7, TradeDetails.select(all, "", "", null, null).size());
        assertThrows(IllegalArgumentException.class, () -> TradeDetails.select(all, null, "UP", null, null));
    }

    @Test
    void filtersBySymbolAndSideAndTheSummaryCoversTheWholeFilteredSet() {
        List<TradeDetailDto> all = sample();
        TradePageDto pg = TradeDetails.page(all, " mu ", "long", "entryDate", "asc", 0, 3);
        long expected = all.stream().filter(t -> t.symbol().equals("MU") && t.side().equals("LONG")).count();
        assertEquals(expected, pg.total());
        assertEquals(expected, pg.summary().trades(), "summary spans all pages, not just this one");
        assertEquals(Math.min(3, expected), pg.items().size());
        pg.items().forEach(t -> { assertEquals("MU", t.symbol()); assertEquals("LONG", t.side()); });
        assertEquals(pg.summary().longs(), pg.summary().trades());
        assertEquals(0, pg.summary().shorts());
    }

    @Test
    void summaryTotalsMatchTheRows() {
        List<TradeDetailDto> all = sample();
        var s = TradeDetails.page(all, null, null, null, null, 0, 1).summary();
        assertEquals(30, s.trades());
        assertEquals(all.stream().mapToDouble(TradeDetailDto::netPnl).sum(), s.netPnl(), 1e-6);
        assertEquals(all.stream().filter(t -> t.netPct() > 0).count(), s.wins());
        assertEquals(100.0 * s.wins() / 30, s.winRatePct(), 1e-9);
        assertEquals(s.grossPnl() - s.commission() - s.slippage(), s.netPnl(), 1e-6);
        assertEquals(all.stream().mapToDouble(TradeDetailDto::netPct).max().orElseThrow(), s.bestNetPct(), 1e-12);
        assertEquals(all.stream().mapToDouble(TradeDetailDto::netPct).min().orElseThrow(), s.worstNetPct(), 1e-12);
    }

    @Test
    void emptyResultsAndOutOfRangePagesAreHandled() {
        TradePageDto none = TradeDetails.page(sample(), "ZZZZ", null, null, null, 0, 10);
        assertEquals(0, none.total());
        assertTrue(none.items().isEmpty());
        assertEquals(0, none.summary().trades());
        assertEquals(0, none.summary().bestNetPct(), 0, "no -Infinity leaking out of an empty summary");
        assertTrue(TradeDetails.page(sample(), null, null, null, null, 99, 10).items().isEmpty());
        assertEquals(0, TradeDetails.page(sample(), null, null, null, null, -5, 10).page(), "negative page clamps to 0");
        assertEquals(TradeDetails.MAX_PAGE_SIZE, TradeDetails.page(sample(), null, null, null, null, 0, 100_000).size());
        assertEquals(1, TradeDetails.page(sample(), null, null, null, null, 0, 0).size());
        assertTrue(TradeDetails.page(List.of(), null, null, null, null, 0, 10).items().isEmpty());
    }

    @Test
    void badInputIsRejectedWithAClearMessage() {
        List<TradeDetailDto> all = sample();
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TradeDetails.page(all, null, null, "netPnL; drop table", null, 0, 10)).getMessage().contains("valid"));
        assertThrows(IllegalArgumentException.class, () -> TradeDetails.page(all, null, "BUY", null, null, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> TradeDetails.page(all, null, null, null, "sideways", 0, 10));
    }

    // ---- the stored per-symbol document must survive a JSON round trip --------------------

    @Test
    void symbolDetailsDocumentRoundTripsThroughJson() {
        JsonCodec codec = new JsonCodec(new ObjectMapper());
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("totalReturnPct", 12.34);
        m.put("cagrPct", 5.5);
        Map<String, Double> years = new LinkedHashMap<>();
        years.put("2025", 7.0);
        years.put("2026", 5.34);
        Map<String, SymbolResult> symbols = new LinkedHashMap<>();
        symbols.put("MU", new SymbolResult(m, years));
        symbols.put("SNDK", new SymbolResult(Map.of("totalReturnPct", -3.0), Map.of("2026", -3.0)));
        BacktestService.SymbolDetails doc = new BacktestService.SymbolDetails(years, symbols);

        BacktestService.SymbolDetails back = codec.read(codec.write(doc), BacktestService.SymbolDetails.class);
        assertEquals(doc, back);
        assertEquals(List.of("MU", "SNDK"), List.copyOf(back.symbols().keySet()), "symbol order is kept");
        assertEquals(List.of("2025", "2026"), List.copyOf(back.symbols().get("MU").yearlyReturnsPct().keySet()));
        assertNull(codec.read(null, BacktestService.SymbolDetails.class), "no stored document reads as null");
        assertNull(codec.read("  ", BacktestService.SymbolDetails.class));
    }
}
