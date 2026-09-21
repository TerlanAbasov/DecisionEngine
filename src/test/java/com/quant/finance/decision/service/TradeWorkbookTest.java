package com.quant.finance.decision.service;

import com.quant.finance.decision.dto.Dtos.TradeDetailDto;
import com.quant.finance.decision.entity.BacktestRunEntity;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TradeWorkbookTest {

    private static BacktestRunEntity run() {
        BacktestRunEntity r = new BacktestRunEntity();
        r.setId(12L);
        r.setStrategyName("rsi_reversion");
        r.setTimeframe("M15");
        r.setStartDate(Instant.parse("2026-01-01T00:00:00Z"));
        r.setEndDate(Instant.parse("2026-09-18T19:45:00Z"));
        r.setCapital(1_000_000);
        r.setCommissionBps(10);
        r.setSlippageBps(20);
        r.setPositionSize(1.0);
        return r;
    }

    private static TradeDetailDto trade(long id, String symbol, String side, boolean open, double netPct, double netPnl) {
        return new TradeDetailDto(id, symbol, side, Instant.parse("2026-09-17T19:45:00Z"), Instant.parse("2026-09-18T19:45:00Z"),
                100, 105.2, 28, open, 1, 1_000_000, 10_000.5, 7.2, 0.05, 0.1, netPct, 7.17,
                72_000, 1_000, 2_000, netPnl);
    }

    private static List<Row> sheet(byte[] xlsx, String name) throws IOException {
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet s = wb.findSheet(name).orElseThrow(() -> new AssertionError("no sheet " + name));
            return s.read();
        }
    }

    @Test
    void tradesSheetHasAHeaderAndOneRowOfRealNumbersAndDatesPerTrade() throws IOException {
        byte[] xlsx = TradeWorkbook.toBytes(run(), null, null,
                List.of(trade(1, "AAPL", "LONG", true, 7.17, 717_046.93), trade(2, "MSFT", "SHORT", false, -0.34, -33_627.14)));
        List<Row> rows = sheet(xlsx, "Trades");

        assertEquals(3, rows.size(), "header + two trades");
        assertEquals("Symbol", rows.get(0).getCellText(0));
        assertEquals("Net P&L $", rows.get(0).getCellText(14));
        assertEquals("Portfolio contrib. %", rows.get(0).getCellText(15));

        Row first = rows.get(1);
        assertEquals("AAPL", first.getCellText(0));
        assertEquals("LONG", first.getCellText(1));
        assertEquals(LocalDateTime.parse("2026-09-17T19:45:00"), first.getCellAsDate(2).orElseThrow());
        assertEquals(105.2, first.getCellAsNumber(5).orElseThrow().doubleValue(), 1e-9);
        assertEquals(28, first.getCellAsNumber(6).orElseThrow().intValue());
        assertEquals("open", first.getCellText(7));
        assertEquals(717_046.93, first.getCellAsNumber(14).orElseThrow().doubleValue(), 1e-9);
        assertEquals(-33_627.14, rows.get(2).getCellAsNumber(14).orElseThrow().doubleValue(), 1e-9);
        assertTrue(rows.get(2).getCellText(7).isEmpty(), "a closed trade has no 'open' mark");
    }

    @Test
    void aValueExcelCannotStoreLeavesTheCellBlankInsteadOfCorruptingTheFile() throws IOException {
        byte[] xlsx = TradeWorkbook.toBytes(run(), null, null, List.of(trade(1, "AAPL", "LONG", false, Double.NaN, Double.POSITIVE_INFINITY)));
        Row row = sheet(xlsx, "Trades").get(1);
        assertTrue(row.getCellAsNumber(13).isEmpty());
        assertTrue(row.getCellAsNumber(14).isEmpty());
        assertEquals("AAPL", row.getCellText(0));
    }

    @Test
    void aRunWithNoMatchingTradesStillExportsAHeader() throws IOException {
        assertEquals(1, sheet(TradeWorkbook.toBytes(run(), "ZZZ", "LONG", List.of()), "Trades").size());
    }

    @Test
    void summarySheetListsTheRunTheFilterAndTheTotals() throws IOException {
        byte[] xlsx = TradeWorkbook.toBytes(run(), " aapl ", "long",
                List.of(trade(1, "AAPL", "LONG", false, 2.0, 100), trade(2, "AAPL", "LONG", false, -1.0, -40)));
        List<String> lines = sheet(xlsx, "Summary").stream().map(r -> r.getCellText(0) + "=" + r.getCellText(1)).toList();

        assertTrue(lines.contains("Strategy=rsi_reversion"), lines.toString());
        assertTrue(lines.contains("Run id=12"), lines.toString());
        assertTrue(lines.contains("Symbol=AAPL"), lines.toString());
        assertTrue(lines.contains("Side=LONG"), lines.toString());
        assertTrue(lines.contains("Trades=2"), lines.toString());
        assertTrue(lines.contains("Win rate %=50.0"), lines.toString());
        assertTrue(lines.contains("Net P&L $=60.0"), lines.toString());
    }
}
