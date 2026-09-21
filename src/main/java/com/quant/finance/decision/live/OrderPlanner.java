package com.quant.finance.decision.live;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        /** Dollars this plan moves: the shares between the account's position and the target, at the latest price. */
        double tradeValue() { return Math.abs(targetQty - positionBefore) * price; }

        boolean addsExposure() { return Math.abs(targetQty) > Math.abs(positionBefore); }

        boolean hasOrders() { return !orders.isEmpty(); }

        SymbolPlan withoutOrders() { return new SymbolPlan(symbol, positionBefore, targetQty, virtualQty, List.of(), price); }
    }

    public record Skipped(String symbol, String reason) {}

    public record Plan(List<SymbolPlan> symbols, List<Skipped> skipped) {
        /** Symbols that need at least one order. */
        public List<SymbolPlan> withOrders() { return symbols.stream().filter(SymbolPlan::hasOrders).toList(); }

        public int orderCount() { return symbols.stream().mapToInt(s -> s.orders().size()).sum(); }
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
        for (Map.Entry<String, Double> e : new TreeMap<>(virtualQty).entrySet()) {
            String sym = e.getKey();
            Double price = prices.get(sym);
            if (price == null || !(price > 0)) continue;
            double actual = actualQty.getOrDefault(sym, 0.0);
            long target = wholeShares(e.getValue());
            plans.add(new SymbolPlan(sym, actual, target, e.getValue(), legs(sym, actual, target), price));
        }

        List<Skipped> skipped = new ArrayList<>();
        if (maxGrossUsd > 0) plans = capExposure(plans, maxGrossUsd, skipped);
        if (maxOrders > 0) plans = capOrderCount(plans, maxOrders, skipped);
        return new Plan(plans, skipped);
    }

    /** Holds back exposure-increasing plans, biggest first, until the book fits {@code maxGrossUsd}. */
    private static List<SymbolPlan> capExposure(List<SymbolPlan> plans, double maxGrossUsd, List<Skipped> skipped) {
        List<SymbolPlan> result = new ArrayList<>(plans);
        while (grossAfterOrders(result) > maxGrossUsd + 1e-6) {
            SymbolPlan biggest = result.stream().filter(p -> p.hasOrders() && p.addsExposure())
                    .max(Comparator.comparingDouble(SymbolPlan::tradeValue))
                    .orElse(null);
            if (biggest == null) break;                                // nothing left to hold back
            skipped.add(new Skipped(biggest.symbol(), EXPOSURE_CAP));
            result.replaceAll(p -> p == biggest ? p.withoutOrders() : p);
        }
        return result;
    }

    /** Keeps at most {@code maxOrders} orders: the plans that reduce exposure first, then the largest. */
    private static List<SymbolPlan> capOrderCount(List<SymbolPlan> plans, int maxOrders, List<Skipped> skipped) {
        List<SymbolPlan> withOrders = plans.stream().filter(SymbolPlan::hasOrders).toList();
        if (withOrders.stream().mapToInt(p -> p.orders().size()).sum() <= maxOrders) return plans;

        Comparator<SymbolPlan> priority = Comparator
                .comparing((SymbolPlan p) -> !p.orders().get(0).reducesExposure())
                .thenComparing(Comparator.comparingDouble(SymbolPlan::tradeValue).reversed());
        Set<String> kept = new HashSet<>();
        int used = 0;
        for (SymbolPlan p : withOrders.stream().sorted(priority).toList()) {
            if (used + p.orders().size() <= maxOrders) {
                kept.add(p.symbol());
                used += p.orders().size();
            } else {
                skipped.add(new Skipped(p.symbol(), ORDER_LIMIT));
            }
        }
        return plans.stream().map(p -> !p.hasOrders() || kept.contains(p.symbol()) ? p : p.withoutOrders()).toList();
    }

    /** Orders that take the account from {@code actual} to {@code target}; two when that crosses zero. */
    static List<Order> legs(String sym, double actual, long target) {
        long delta = Math.round(target - actual);
        if (delta == 0) return List.of();
        String side = delta > 0 ? "buy" : "sell";
        boolean crossesZero = (actual > 0.5 && target < 0) || (actual < -0.5 && target > 0);
        if (crossesZero)
            return List.of(new Order(sym, side, Math.round(Math.abs(actual)), true), new Order(sym, side, Math.abs(target), false));
        return List.of(new Order(sym, side, Math.abs(delta), Math.abs(target) < Math.abs(actual)));
    }

    /** Gross exposure ($) of the book if every planned order fills; a symbol without orders keeps its current position. */
    private static double grossAfterOrders(List<SymbolPlan> plans) {
        return plans.stream().mapToDouble(p -> (p.hasOrders() ? Math.abs(p.targetQty()) : Math.abs(p.positionBefore())) * p.price()).sum();
    }
}
