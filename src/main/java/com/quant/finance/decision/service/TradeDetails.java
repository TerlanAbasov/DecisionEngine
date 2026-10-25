package com.quant.finance.decision.service;

import com.quant.finance.decision.dto.Dtos.TradeDetailDto;
import com.quant.finance.decision.dto.Dtos.TradePageDto;
import com.quant.finance.decision.dto.Dtos.TradeSummaryDto;
import com.quant.finance.decision.entity.TradeEntity;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns stored trades into {@link TradeDetailDto} rows and filters / sorts / pages them: pure functions over loaded data (at most tens of thousands of trades),
 * so paging in memory is simpler than SQL and every sortable column can be derived.
 */
final class TradeDetails {
    private TradeDetails() {}

    static final int MAX_PAGE_SIZE = 500;
    static final int DEFAULT_PAGE_SIZE = 50;

    /** The run-level settings a trade's money and cost figures are derived from. */
    record RunCosts(double capital, double commissionBps, double slippageBps, double positionSize) {
        double costRate() {
            return (commissionBps + slippageBps) / 1e4;
        }
        /** Share of every fill's cost that is commission (the rest is slippage). */
        double commissionShare() {
            double total = commissionBps + slippageBps;
            return total > 0 ? commissionBps / total : 0;
        }
    }

    /**
     * Rows saved before the accounting columns only have entry/exit price, so gross is the price move, cost one fill each way at the run's rates and size the run's position size;
     * newer rows carry the engine's exact values.
     */
    static TradeDetailDto toDto(TradeEntity trade, RunCosts costs) {
        int side = "LONG".equals(trade.getSide()) ? 1 : -1;
        boolean stored = trade.getGrossReturn() != null && trade.getCost() != null;
        double exposure = trade.getExposure() != null ? trade.getExposure() : costs.positionSize();
        double gross;
        double cost;
        if (stored) {
            gross = trade.getGrossReturn();
            cost = trade.getCost();
        } else {
            double move = trade.getEntryPx() > 0 ? trade.getExitPx() / trade.getEntryPx() - 1 : 0;
            gross = exposure * side * move;
            cost = exposure * 2 * costs.costRate();
        }
        double net = gross - cost;
        double commission = cost * costs.commissionShare();
        double slippage = cost - commission;

        double notional = costs.capital() * exposure;
        double shares = trade.getEntryPx() > 0 ? notional / trade.getEntryPx() : 0;
        // percentages are returns on the position's own notional, so a half-size trade that
        // moved 2% reads 2%, while the money columns are what it earned on the whole capital
        double percentPerUnit = exposure > 0 ? 100.0 / exposure : 0;
        return new TradeDetailDto(
                trade.getId() == null ? 0 : trade.getId(),
                trade.getSymbol(),
                trade.getSide(),
                trade.getEntryDate(),
                trade.getExitDate(),
                trade.getEntryPx(),
                trade.getExitPx(),
                trade.getBars(),
                Boolean.TRUE.equals(trade.getStillOpen()),
                exposure,
                notional,
                shares,
                gross * percentPerUnit,
                commission * percentPerUnit,
                slippage * percentPerUnit,
                net * percentPerUnit,
                trade.getReturnPct() * 100.0,
                costs.capital() * gross,
                costs.capital() * commission,
                costs.capital() * slippage,
                costs.capital() * net);
    }

    private static final Map<String, Comparator<TradeDetailDto>> SORTS = Map.ofEntries(
            Map.entry(
                    "entryDate",
                    Comparator.comparing(TradeDetailDto::entryDate, Comparator.nullsLast(Comparator.naturalOrder()))),
            Map.entry(
                    "exitDate",
                    Comparator.comparing(TradeDetailDto::exitDate, Comparator.nullsLast(Comparator.naturalOrder()))),
            Map.entry(
                    "symbol",
                    Comparator.comparing(TradeDetailDto::symbol, Comparator.nullsLast(Comparator.naturalOrder()))),
            Map.entry(
                    "side",
                    Comparator.comparing(TradeDetailDto::side, Comparator.nullsLast(Comparator.naturalOrder()))),
            Map.entry("bars", Comparator.comparingInt(TradeDetailDto::bars)),
            Map.entry("entryPx", Comparator.comparingDouble(TradeDetailDto::entryPx)),
            Map.entry("exitPx", Comparator.comparingDouble(TradeDetailDto::exitPx)),
            Map.entry("grossPct", Comparator.comparingDouble(TradeDetailDto::grossPct)),
            Map.entry("netPct", Comparator.comparingDouble(TradeDetailDto::netPct)),
            Map.entry("contribPct", Comparator.comparingDouble(TradeDetailDto::contribPct)),
            Map.entry("commission", Comparator.comparingDouble(TradeDetailDto::commission)),
            Map.entry("netPnl", Comparator.comparingDouble(TradeDetailDto::netPnl)));

    static boolean isSortable(String key) {
        return SORTS.containsKey(key);
    }

    /** The trades matching the filter (symbol, LONG/SHORT side; case-insensitive, blank = all), sorted by a sortable key (default {@code entryDate}, "desc"). */
    static List<TradeDetailDto> select(List<TradeDetailDto> all, String symbol, String side, String sort, String dir) {
        String symbolFilter =
                symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase(Locale.ROOT);
        String sideFilter = side == null || side.isBlank() ? null : side.trim().toUpperCase(Locale.ROOT);
        if (sideFilter != null && !sideFilter.equals("LONG") && !sideFilter.equals("SHORT")) {
            throw new IllegalArgumentException("side must be LONG or SHORT, got: " + side);
        }
        String key = sort == null || sort.isBlank() ? "entryDate" : sort.trim();
        Comparator<TradeDetailDto> comparator = SORTS.get(key);
        if (comparator == null) {
            throw new IllegalArgumentException("Unknown sort '" + sort + "'; valid: "
                    + SORTS.keySet().stream().sorted().toList());
        }
        String direction = dir == null || dir.isBlank() ? "desc" : dir.trim().toLowerCase(Locale.ROOT);
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw new IllegalArgumentException("dir must be asc or desc, got: " + dir);
        }
        if (direction.equals("desc")) {
            comparator = comparator.reversed();
        }
        comparator = comparator.thenComparingLong(TradeDetailDto::id); // stable, so paging never repeats or skips rows

        return all.stream()
                .filter(t -> symbolFilter == null || symbolFilter.equalsIgnoreCase(t.symbol()))
                .filter(t -> sideFilter == null || sideFilter.equals(t.side()))
                .sorted(comparator)
                .toList();
    }

    /** One page of {@link #select}, with totals over every matching trade; throws IllegalArgumentException for an unknown sort key, side or direction. */
    static TradePageDto page(
            List<TradeDetailDto> all, String symbol, String side, String sort, String dir, int page, int size) {
        List<TradeDetailDto> rows = select(all, symbol, side, sort, dir);
        int pageIndex = Math.max(0, page);
        int pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        long from = (long) pageIndex * pageSize;
        List<TradeDetailDto> items = from >= rows.size()
                ? List.of()
                : rows.subList((int) from, (int) Math.min(rows.size(), from + pageSize));
        return new TradePageDto(items, rows.size(), pageIndex, pageSize, summarize(rows));
    }

    static TradeSummaryDto summarize(List<TradeDetailDto> rows) {
        long longs = 0;
        long wins = 0;
        double gross = 0;
        double commission = 0;
        double slippage = 0;
        double net = 0;
        double sumPct = 0;
        double best = Double.NEGATIVE_INFINITY;
        double worst = Double.POSITIVE_INFINITY;
        for (TradeDetailDto row : rows) {
            if ("LONG".equals(row.side())) {
                longs++;
            }
            if (row.netPct() > 0) {
                wins++;
            }
            gross += row.grossPnl();
            commission += row.commission();
            slippage += row.slippage();
            net += row.netPnl();
            sumPct += row.netPct();
            best = Math.max(best, row.netPct());
            worst = Math.min(worst, row.netPct());
        }
        long count = rows.size();
        return new TradeSummaryDto(
                count,
                longs,
                count - longs,
                wins,
                count > 0 ? 100.0 * wins / count : 0,
                gross,
                commission,
                slippage,
                net,
                count > 0 ? sumPct / count : 0,
                count > 0 ? best : 0,
                count > 0 ? worst : 0);
    }
}
