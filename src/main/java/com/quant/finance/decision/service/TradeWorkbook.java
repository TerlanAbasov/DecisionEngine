package com.quant.finance.decision.service;

import com.quant.finance.decision.dto.Dtos.TradeDetailDto;
import com.quant.finance.decision.dto.Dtos.TradeSummaryDto;
import com.quant.finance.decision.entity.BacktestRunEntity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

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
        for (int row = 0; row < rows.size(); row++) {
            for (int c = 0; c < COLUMNS.size(); c++) {
                Column column = COLUMNS.get(c);
                write(sheet, row + 1, c, column.value().apply(rows.get(row)), column.format());
            }
        }
        sheet.freezePane(0, 1); // keep the header in view
        sheet.setAutoFilter(0, 0, rows.size(), COLUMNS.size() - 1);
    }

    private static void writeSummary(
            Worksheet sheet, BacktestRunEntity run, String symbol, String side, List<TradeDetailDto> rows) {
        TradeSummaryDto summary = TradeDetails.summarize(rows);
        sheet.width(0, 22);
        sheet.width(1, 24);
        int row = 0;
        row = section(sheet, row, "Run");
        row = line(sheet, row, "Run id", run.getId(), null);
        row = line(sheet, row, "Strategy", run.getStrategyName(), null);
        row = line(sheet, row, "Timeframe", run.getTimeframe(), null);
        row = line(sheet, row, "From", utc(run.getStartDate()), DATE_TIME);
        row = line(sheet, row, "To", utc(run.getEndDate()), DATE_TIME);
        row = line(sheet, row, "Starting capital $", run.getCapital(), MONEY);
        row = line(sheet, row, "Commission (bps)", run.getCommissionBps(), PCT);
        row = line(sheet, row, "Slippage (bps)", run.getSlippageBps(), PCT);
        row = line(sheet, row, "Position size", run.getPositionSize(), PCT);

        row = section(sheet, row + 1, "Filter");
        row = line(
                sheet,
                row,
                "Symbol",
                StringUtils.isBlank(symbol) ? "All" : symbol.trim().toUpperCase(Locale.ROOT),
                null);
        row = line(
                sheet,
                row,
                "Side",
                StringUtils.isBlank(side) ? "Long & short" : side.trim().toUpperCase(Locale.ROOT),
                null);

        row = section(sheet, row + 1, "Totals over the exported trades");
        row = line(sheet, row, "Trades", summary.trades(), "#,##0");
        row = line(sheet, row, "Long", summary.longs(), "#,##0");
        row = line(sheet, row, "Short", summary.shorts(), "#,##0");
        row = line(sheet, row, "Win rate %", summary.winRatePct(), PCT);
        row = line(sheet, row, "Gross P&L $", summary.grossPnl(), MONEY);
        row = line(sheet, row, "Commission $", summary.commission(), MONEY);
        row = line(sheet, row, "Slippage $", summary.slippage(), MONEY);
        row = line(sheet, row, "Net P&L $", summary.netPnl(), MONEY);
        row = line(sheet, row, "Average net %", summary.avgNetPct(), PCT);
        row = line(sheet, row, "Best net %", summary.bestNetPct(), PCT);
        line(sheet, row, "Worst net %", summary.worstNetPct(), PCT);
    }

    /** A bold heading on row {@code r}; returns the next row. */
    private static int section(Worksheet sheet, int row, String title) {
        sheet.value(row, 0, title);
        sheet.style(row, 0).bold().fillColor(HEADER_FILL).set();
        sheet.style(row, 1).fillColor(HEADER_FILL).set();
        return row + 1;
    }

    /** A label and its value on row {@code r}; returns the next row. */
    private static int line(Worksheet sheet, int row, String label, Object value, String format) {
        sheet.value(row, 0, label);
        write(sheet, row, 1, value, format);
        return row + 1;
    }

    /** Writes one cell; an empty or non-finite value (which Excel cannot store) leaves it blank. */
    private static void write(Worksheet sheet, int row, int col, Object value, String format) {
        switch (value) {
            case null -> {
                return;
            }
            case Number number
            when !Double.isFinite(number.doubleValue()) -> {
                return;
            }
            case Number number -> sheet.value(row, col, number);
            case String text -> sheet.value(row, col, text);
            case LocalDateTime dateTime -> sheet.value(row, col, dateTime);
            default -> throw new IllegalArgumentException("Unsupported cell value: " + value.getClass());
        }
        if (format != null) {
            sheet.style(row, col).format(format).set();
        }
    }

    /** Excel has no time zones: dates are written as UTC, which is how the trades table shows them. */
    private static LocalDateTime utc(Instant time) {
        return time == null ? null : LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }
}
