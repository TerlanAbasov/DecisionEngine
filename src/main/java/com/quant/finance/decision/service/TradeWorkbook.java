package com.quant.finance.decision.service;

import com.quant.finance.decision.dto.Dtos.TradeDetailDto;
import com.quant.finance.decision.dto.Dtos.TradeSummaryDto;
import com.quant.finance.decision.entity.BacktestRunEntity;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * A run's trades as an .xlsx workbook for analysis in Excel: a "Trades" sheet with one row per trade and the same figures as the trades table
 * (real numbers and dates, so it can be sorted, filtered and pivoted), and a "Summary" sheet with the run's settings and the totals. Pure functions.
 */
final class TradeWorkbook {
    private TradeWorkbook() {}

    private static final String DATE_TIME = "yyyy-mm-dd hh:mm";
    private static final String MONEY = "#,##0.00";
    private static final String PRICE = "0.00##";
    private static final String PCT = "0.00";
    private static final String HEADER_FILL = "DDE3EA";

    /** One column of the Trades sheet: its header, width, number format (null = general) and where its value comes from. */
    private record Column(String header, double width, String format, Function<TradeDetailDto, Object> value) {}

    private static final List<Column> COLUMNS = List.of(
            new Column("Symbol", 9, null, TradeDetailDto::symbol),
            new Column("Side", 8, null, TradeDetailDto::side),
            new Column("Entry", 17, DATE_TIME, t -> utc(t.entryDate())),
            new Column("Entry price", 12, PRICE, TradeDetailDto::entryPx),
            new Column("Exit", 17, DATE_TIME, t -> utc(t.exitDate())),
            new Column("Exit price", 12, PRICE, TradeDetailDto::exitPx),
            new Column("Bars", 7, "0", TradeDetailDto::bars),
            new Column("Open", 7, null, t -> t.open() ? "open" : null),
            new Column("Shares", 13, MONEY, TradeDetailDto::shares),
            new Column("Gross %", 10, PCT, TradeDetailDto::grossPct),
            new Column("Gross P&L $", 14, MONEY, TradeDetailDto::grossPnl),
            new Column("Commission $", 14, MONEY, TradeDetailDto::commission),
            new Column("Slippage $", 13, MONEY, TradeDetailDto::slippage),
            new Column("Net %", 10, PCT, TradeDetailDto::netPct),
            new Column("Net P&L $", 14, MONEY, TradeDetailDto::netPnl),
            new Column("Portfolio contrib. %", 19, "0.000", TradeDetailDto::contribPct));

    /** The workbook for {@code rows}, the trades of {@code run} left after filtering by {@code symbol} and {@code side} (blank = all). */
    static byte[] toBytes(BacktestRunEntity run, String symbol, String side, List<TradeDetailDto> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(out, "DecisionEngine", "1.0")) {
            writeTrades(workbook.newWorksheet("Trades"), rows);
            writeSummary(workbook.newWorksheet("Summary"), run, symbol, side, rows);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the Excel export", e);
        }
        return out.toByteArray();
    }

    private static void writeTrades(Worksheet sheet, List<TradeDetailDto> rows) {
        for (int c = 0; c < COLUMNS.size(); c++) {
            sheet.width(c, COLUMNS.get(c).width());
            sheet.value(0, c, COLUMNS.get(c).header());
            sheet.style(0, c).bold().fillColor(HEADER_FILL).set();
        }
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < COLUMNS.size(); c++) {
                Column column = COLUMNS.get(c);
                write(sheet, r + 1, c, column.value().apply(rows.get(r)), column.format());
            }
        }
        sheet.freezePane(0, 1);                                        // keep the header in view
        sheet.setAutoFilter(0, 0, rows.size(), COLUMNS.size() - 1);
    }

    private static void writeSummary(Worksheet sheet, BacktestRunEntity run, String symbol, String side, List<TradeDetailDto> rows) {
        TradeSummaryDto s = TradeDetails.summarize(rows);
        sheet.width(0, 22);
        sheet.width(1, 24);
        int r = 0;
        r = section(sheet, r, "Run");
        r = line(sheet, r, "Run id", run.getId(), null);
        r = line(sheet, r, "Strategy", run.getStrategyName(), null);
        r = line(sheet, r, "Timeframe", run.getTimeframe(), null);
        r = line(sheet, r, "From", utc(run.getStartDate()), DATE_TIME);
        r = line(sheet, r, "To", utc(run.getEndDate()), DATE_TIME);
        r = line(sheet, r, "Starting capital $", run.getCapital(), MONEY);
        r = line(sheet, r, "Commission (bps)", run.getCommissionBps(), PCT);
        r = line(sheet, r, "Slippage (bps)", run.getSlippageBps(), PCT);
        r = line(sheet, r, "Position size", run.getPositionSize(), PCT);

        r = section(sheet, r + 1, "Filter");
        r = line(sheet, r, "Symbol", isBlank(symbol) ? "All" : symbol.trim().toUpperCase(Locale.ROOT), null);
        r = line(sheet, r, "Side", isBlank(side) ? "Long & short" : side.trim().toUpperCase(Locale.ROOT), null);

        r = section(sheet, r + 1, "Totals over the exported trades");
        r = line(sheet, r, "Trades", s.trades(), "#,##0");
        r = line(sheet, r, "Long", s.longs(), "#,##0");
        r = line(sheet, r, "Short", s.shorts(), "#,##0");
        r = line(sheet, r, "Win rate %", s.winRatePct(), PCT);
        r = line(sheet, r, "Gross P&L $", s.grossPnl(), MONEY);
        r = line(sheet, r, "Commission $", s.commission(), MONEY);
        r = line(sheet, r, "Slippage $", s.slippage(), MONEY);
        r = line(sheet, r, "Net P&L $", s.netPnl(), MONEY);
        r = line(sheet, r, "Average net %", s.avgNetPct(), PCT);
        r = line(sheet, r, "Best net %", s.bestNetPct(), PCT);
        line(sheet, r, "Worst net %", s.worstNetPct(), PCT);
    }

    /** A bold heading on row {@code r}; returns the next row. */
    private static int section(Worksheet sheet, int r, String title) {
        sheet.value(r, 0, title);
        sheet.style(r, 0).bold().fillColor(HEADER_FILL).set();
        sheet.style(r, 1).fillColor(HEADER_FILL).set();
        return r + 1;
    }

    /** A label and its value on row {@code r}; returns the next row. */
    private static int line(Worksheet sheet, int r, String label, Object value, String format) {
        sheet.value(r, 0, label);
        write(sheet, r, 1, value, format);
        return r + 1;
    }

    /** Writes one cell; an empty or non-finite value (which Excel cannot store) leaves it blank. */
    private static void write(Worksheet sheet, int row, int col, Object value, String format) {
        switch (value) {
            case null -> { return; }
            case Number n when !Double.isFinite(n.doubleValue()) -> { return; }
            case Number n -> sheet.value(row, col, n);
            case String s -> sheet.value(row, col, s);
            case LocalDateTime d -> sheet.value(row, col, d);
            default -> throw new IllegalArgumentException("Unsupported cell value: " + value.getClass());
        }
        if (format != null) sheet.style(row, col).format(format).set();
    }

    /** Excel has no time zones: dates are written as UTC, which is how the trades table shows them. */
    private static LocalDateTime utc(Instant t) { return t == null ? null : LocalDateTime.ofInstant(t, ZoneOffset.UTC); }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
