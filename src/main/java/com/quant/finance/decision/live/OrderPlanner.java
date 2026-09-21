package com.quant.finance.decision.live;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns the strategies' net virtual shares per symbol into orders: the whole-share position to hold minus what the account holds, reducing orders first;
 * the exposure and order-count caps only hold back orders that add exposure or come after the limit. Pure functions.
 */
public final class OrderPlanner {
    private OrderPlanner() {}

    /** One broker order. A move through zero (long to short) is two orders — close, then open — never one. */
    public record Order(String symbol, String side, long qty, boolean reducesExposure) {}

    /** Everything to do for one symbol this cycle. {@code targetQty} is the whole-share position wanted. */
    public record SymbolPlan(String symbol, double positionBefore, long targetQty, double virtualQty,
                             List<Order> orders, double price) {
        public long orderedQty() { return orders.stream().mapToLong(Order::qty).sum(); }
    }

    public record Skipped(String symbol, String reason) {}

    public record Plan(List<SymbolPlan> symbols, List<Skipped> skipped) {
        /** Symbols that need at least one order. */
        public List<SymbolPlan> withOrders() { return symbols.stream().filter(s -> !s.orders().isEmpty()).toList(); }
    }

    public static final String EXPOSURE_CAP = "EXPOSURE_CAP", ORDER_LIMIT = "ORDER_LIMIT";

    /** Nearest whole share, ties away from zero. */
    public static long wholeShares(double virtualQty) {
        return (long) (Math.signum(virtualQty) * Math.floor(Math.abs(virtualQty) + 0.5));
    }

    /**
     * Plans orders from net virtual shares per symbol (the sum over every slot), the account's signed positions and latest prices;
     * a symbol without a price is not planned.
     */
    public static Plan plan(Map<String, Double> virtualQty, Map<String, Double> actualQty, Map<String, Double> prices,
                            double maxGrossUsd, int maxOrders) {
        List<SymbolPlan> plans = new ArrayList<>();
        Map<String, Double> sorted = new TreeMap<>(virtualQty);
        for (Map.Entry<String, Double> e : sorted.entrySet()) {
            String sym = e.getKey();
            Double price = prices.get(sym);
            if (price == null || !(price > 0)) continue;
            double actual = actualQty.getOrDefault(sym, 0.0);
            long target = wholeShares(e.getValue());
            plans.add(new SymbolPlan(sym, actual, target, e.getValue(), legs(sym, actual, target), price));
        }

        List<Skipped> skipped = new ArrayList<>();

        // gross exposure cap: hold back exposure-increasing plans, biggest first, until the book fits
        if (maxGrossUsd > 0) {
            List<SymbolPlan> ordered = new ArrayList<>(plans);
            while (gross(ordered, true) > maxGrossUsd + 1e-6) {
                SymbolPlan worst = ordered.stream().filter(p -> !p.orders().isEmpty() && addsExposure(p))
                        .max(Comparator.comparingDouble(p -> Math.abs(p.targetQty() - p.positionBefore()) * p.price()))
                        .orElse(null);
                if (worst == null) break;                              // nothing left to hold back
                skipped.add(new Skipped(worst.symbol(), EXPOSURE_CAP));
                ordered.replaceAll(p -> p == worst
                        ? new SymbolPlan(p.symbol(), p.positionBefore(), p.targetQty(), p.virtualQty(), List.of(), p.price()) : p);
            }
            plans = ordered;
        }

        // order limit: keep the orders that reduce exposure first, then the largest
        List<SymbolPlan> withOrders = plans.stream().filter(p -> !p.orders().isEmpty()).toList();
        if (maxOrders > 0 && withOrders.stream().mapToInt(p -> p.orders().size()).sum() > maxOrders) {
            List<SymbolPlan> byPriority = new ArrayList<>(withOrders);
            byPriority.sort(Comparator.<SymbolPlan, Boolean>comparing(p -> !p.orders().get(0).reducesExposure())
                    .thenComparing(Comparator.comparingDouble((SymbolPlan p) -> -Math.abs(p.targetQty() - p.positionBefore()) * p.price())));
            int used = 0;
            List<SymbolPlan> kept = new ArrayList<>();
            for (SymbolPlan p : byPriority) {
                if (used + p.orders().size() <= maxOrders) { kept.add(p); used += p.orders().size(); }
                else skipped.add(new Skipped(p.symbol(), ORDER_LIMIT));
            }
            List<String> keptSymbols = kept.stream().map(SymbolPlan::symbol).toList();
            plans = plans.stream().map(p -> p.orders().isEmpty() || keptSymbols.contains(p.symbol()) ? p
                    : new SymbolPlan(p.symbol(), p.positionBefore(), p.targetQty(), p.virtualQty(), List.of(), p.price())).toList();
        }
        return new Plan(plans, skipped);
    }

    /** Orders that take the account from {@code actual} to {@code target}; two when that crosses zero. */
    static List<Order> legs(String sym, double actual, long target) {
        long delta = Math.round(target - actual);
        if (delta == 0) return List.of();
        double from = actual;
        long qty = Math.abs(delta);
        String side = delta > 0 ? "buy" : "sell";
        boolean crossesZero = (from > 0.5 && target < 0) || (from < -0.5 && target > 0);
        if (crossesZero) {
            long closeQty = Math.round(Math.abs(from));
            long openQty = Math.abs(target);
            return List.of(new Order(sym, side, closeQty, true), new Order(sym, side, openQty, false));
        }
        boolean reduces = Math.abs(target) < Math.abs(from);
        return List.of(new Order(sym, side, qty, reduces));
    }

    private static boolean addsExposure(SymbolPlan p) {
        return Math.abs(p.targetQty()) > Math.abs(p.positionBefore());
    }

    /** Gross exposure ($) of the book if every planned order fills; without orders, of the current positions. */
    private static double gross(List<SymbolPlan> plans, boolean afterOrders) {
        double g = 0;
        for (SymbolPlan p : plans)
            g += (afterOrders && !p.orders().isEmpty() ? Math.abs(p.targetQty()) : Math.abs(p.positionBefore())) * p.price();
        return g;
    }
}
