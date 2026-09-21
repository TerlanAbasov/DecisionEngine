package com.quant.finance.decision.service;

import com.quant.finance.decision.entity.TradeEntity;
import com.quant.finance.decision.dto.Dtos.TradeDetailDto;
import com.quant.finance.decision.dto.Dtos.TradePageDto;
import com.quant.finance.decision.dto.Dtos.TradeSummaryDto;

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
        double costRate() { return (commissionBps + slippageBps) / 1e4; }
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
    static TradeDetailDto toDto(TradeEntity t, RunCosts rc) {
        int side = "LONG".equals(t.getSide()) ? 1 : -1;
        boolean stored = t.getGrossReturn() != null && t.getCost() != null;
        double exposure = t.getExposure() != null ? t.getExposure() : rc.positionSize();
        double gross, cost;
        if (stored) {
            gross = t.getGrossReturn();
            cost = t.getCost();
        } else {
            double move = t.getEntryPx() > 0 ? t.getExitPx() / t.getEntryPx() - 1 : 0;
            gross = exposure * side * move;
            cost = exposure * 2 * rc.costRate();
        }
        double net = gross - cost;
        double commission = cost * rc.commissionShare();
        double slippage = cost - commission;

        double notional = rc.capital() * exposure;
        double shares = t.getEntryPx() > 0 ? notional / t.getEntryPx() : 0;
        // percentages are returns on the position's own notional, so a half-size trade that
        // moved 2% reads 2%, while the money columns are what it earned on the whole capital
        double per = exposure > 0 ? 100.0 / exposure : 0;
        return new TradeDetailDto(t.getId() == null ? 0 : t.getId(), t.getSymbol(), t.getSide(),
                t.getEntryDate(), t.getExitDate(), t.getEntryPx(), t.getExitPx(), t.getBars(),
                Boolean.TRUE.equals(t.getStillOpen()),
                exposure, notional, shares,
                gross * per, commission * per, slippage * per, net * per, t.getReturnPct() * 100.0,
                rc.capital() * gross, rc.capital() * commission, rc.capital() * slippage, rc.capital() * net);
    }

    private static final Map<String, Comparator<TradeDetailDto>> SORTS = Map.ofEntries(
            Map.entry("entryDate", Comparator.comparing(TradeDetailDto::entryDate, Comparator.nullsLast(Comparator.naturalOrder()))),
            Map.entry("exitDate", Comparator.comparing(TradeDetailDto::exitDate, Comparator.nullsLast(Comparator.naturalOrder()))),
            Map.entry("symbol", Comparator.comparing(TradeDetailDto::symbol, Comparator.nullsLast(Comparator.naturalOrder()))),
            Map.entry("side", Comparator.comparing(TradeDetailDto::side, Comparator.nullsLast(Comparator.naturalOrder()))),
            Map.entry("bars", Comparator.comparingInt(TradeDetailDto::bars)),
            Map.entry("entryPx", Comparator.comparingDouble(TradeDetailDto::entryPx)),
            Map.entry("exitPx", Comparator.comparingDouble(TradeDetailDto::exitPx)),
            Map.entry("grossPct", Comparator.comparingDouble(TradeDetailDto::grossPct)),
            Map.entry("netPct", Comparator.comparingDouble(TradeDetailDto::netPct)),
            Map.entry("contribPct", Comparator.comparingDouble(TradeDetailDto::contribPct)),
            Map.entry("commission", Comparator.comparingDouble(TradeDetailDto::commission)),
            Map.entry("netPnl", Comparator.comparingDouble(TradeDetailDto::netPnl)));

    static boolean isSortable(String key) { return SORTS.containsKey(key); }

    /** The trades matching the filter (symbol, LONG/SHORT side; case-insensitive, blank = all), sorted by a sortable key (default {@code entryDate}, "desc"). */
    static List<TradeDetailDto> select(List<TradeDetailDto> all, String symbol, String side, String sort, String dir) {
        String sym = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase(Locale.ROOT);
        String sd = side == null || side.isBlank() ? null : side.trim().toUpperCase(Locale.ROOT);
        if (sd != null && !sd.equals("LONG") && !sd.equals("SHORT"))
            throw new IllegalArgumentException("side must be LONG or SHORT, got: " + side);
        String key = sort == null || sort.isBlank() ? "entryDate" : sort.trim();
        Comparator<TradeDetailDto> cmp = SORTS.get(key);
        if (cmp == null)
            throw new IllegalArgumentException("Unknown sort '" + sort + "'; valid: " + SORTS.keySet().stream().sorted().toList());
        String d = dir == null || dir.isBlank() ? "desc" : dir.trim().toLowerCase(Locale.ROOT);
        if (!d.equals("asc") && !d.equals("desc"))
            throw new IllegalArgumentException("dir must be asc or desc, got: " + dir);
        if (d.equals("desc")) cmp = cmp.reversed();
        cmp = cmp.thenComparingLong(TradeDetailDto::id);       // stable, so paging never repeats or skips rows

        return all.stream()
                .filter(t -> sym == null || sym.equalsIgnoreCase(t.symbol()))
                .filter(t -> sd == null || sd.equals(t.side()))
                .sorted(cmp)
                .toList();
    }

    /** One page of {@link #select}, with totals over every matching trade; throws IllegalArgumentException for an unknown sort key, side or direction. */
    static TradePageDto page(List<TradeDetailDto> all, String symbol, String side,
                             String sort, String dir, int page, int size) {
        List<TradeDetailDto> rows = select(all, symbol, side, sort, dir);
        int p = Math.max(0, page);
        int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        long from = (long) p * s;
        List<TradeDetailDto> items = from >= rows.size() ? List.of()
                : rows.subList((int) from, (int) Math.min(rows.size(), from + s));
        return new TradePageDto(items, rows.size(), p, s, summarize(rows));
    }

    static TradeSummaryDto summarize(List<TradeDetailDto> rows) {
        long longs = 0, wins = 0;
        double gross = 0, commission = 0, slippage = 0, net = 0, sumPct = 0;
        double best = Double.NEGATIVE_INFINITY, worst = Double.POSITIVE_INFINITY;
        for (TradeDetailDto t : rows) {
            if ("LONG".equals(t.side())) longs++;
            if (t.netPct() > 0) wins++;
            gross += t.grossPnl(); commission += t.commission(); slippage += t.slippage(); net += t.netPnl();
            sumPct += t.netPct();
            best = Math.max(best, t.netPct()); worst = Math.min(worst, t.netPct());
        }
        long n = rows.size();
        return new TradeSummaryDto(n, longs, n - longs, wins, n > 0 ? 100.0 * wins / n : 0,
                gross, commission, slippage, net,
                n > 0 ? sumPct / n : 0, n > 0 ? best : 0, n > 0 ? worst : 0);
    }
}
