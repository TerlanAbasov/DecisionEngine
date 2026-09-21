package com.quant.finance.decision.live;

import com.quant.finance.decision.error.AlpacaApiException;
import com.quant.finance.decision.domain.*;
import com.quant.finance.decision.live.AlpacaModels.*;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

/** Test doubles for the paper-trading job: a broker, a data source, a store, a clock and constant-signal strategies. */
final class LiveFakes {
    private LiveFakes() {}

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(long millis) { now = now.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static final class FakeGateway implements TradingGateway {
        boolean paper = true, marketOpen = true, neverFill = false;
        double fillFraction = 1.0;
        AccountInfo account = new AccountInfo("ACTIVE", 100_000, 100_000, 200_000, 0, 0, false, false, true);
        final Map<String, Double> positions = new HashMap<>();
        final Map<String, Double> prices;
        final Set<String> reject = new HashSet<>();
        final Map<String, AssetInfo> assets = new HashMap<>();
        final List<OrderInfo> submitted = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        final List<OrderInfo> leftovers = new ArrayList<>();
        private final Map<String, OrderInfo> orders = new HashMap<>();
        private int seq;

        FakeGateway(Map<String, Double> prices) { this.prices = prices; }

        @Override public boolean isPaper() { return paper; }
        @Override public AccountInfo account() { return account; }
        @Override public MarketClock clock() { return new MarketClock(marketOpen, Instant.now(), Instant.parse("2026-09-22T13:30:00Z"), null); }

        @Override public List<PositionInfo> positions() {
            List<PositionInfo> out = new ArrayList<>();
            positions.forEach((s, q) -> { if (Math.abs(q) > 1e-9) out.add(new PositionInfo(s, q, 0, prices.getOrDefault(s, 0.0), 0, 0)); });
            return out;
        }

        @Override public AssetInfo asset(String symbol) {
            return assets.getOrDefault(symbol, new AssetInfo(symbol, true, true, true, true, "active"));
        }

        @Override public OrderInfo submitMarketOrder(String symbol, long qty, String side, String clientOrderId) {
            if (reject.contains(symbol)) throw new AlpacaApiException(403, "insufficient buying power");
            String id = "o" + (++seq);
            OrderInfo o;
            if (neverFill) {
                o = new OrderInfo(id, clientOrderId, symbol, side, qty, "new", 0, null, Instant.now(), null);
            } else {
                double filled = qty * fillFraction;
                positions.merge(symbol, side.equals("buy") ? filled : -filled, Double::sum);
                o = new OrderInfo(id, clientOrderId, symbol, side, qty, fillFraction >= 1 ? "filled" : "canceled", filled,
                        filled > 0 ? prices.get(symbol) : null, Instant.now(), Instant.now());
            }
            orders.put(id, o);
            submitted.add(o);
            return o;
        }

        @Override public Optional<OrderInfo> findByClientOrderId(String c) { return orders.values().stream().filter(o -> o.clientOrderId().equals(c)).findFirst(); }
        @Override public OrderInfo order(String id) { return orders.get(id); }

        @Override public void cancelOrder(String id) {
            cancelled.add(id);
            OrderInfo o = orders.get(id);
            if (o != null) orders.put(id, new OrderInfo(o.id(), o.clientOrderId(), o.symbol(), o.side(), o.qty(), "canceled", o.filledQty(), o.filledAvgPrice(), o.submittedAt(), o.filledAt()));
        }

        @Override public List<OrderInfo> openOrders() { return leftovers; }
    }

    static final class FakeData implements LiveDataSource {
        final Map<String, Double> prices;
        final Set<String> failBars = new HashSet<>();
        final Instant now;
        int barCalls;

        FakeData(Map<String, Double> prices, Instant now) { this.prices = prices; this.now = now; }

        @Override public BarSeries bars(String symbol, Base base, int lookbackDays) {
            barCalls++;
            if (failBars.contains(symbol)) throw new AlpacaApiException(500, "data down");
            int n = 900;                                   // 15 hours of 1-minute bars, ending a minute ago
            Instant[] d = new Instant[n];
            double[] px = new double[n];
            for (int i = 0; i < n; i++) { d[i] = now.minus(Duration.ofMinutes(n - i)); px[i] = 100; }
            return new BarSeries(symbol, d, px, px, px, px, px);
        }

        @Override public Map<String, Double> latestPrices(Collection<String> symbols) {
            Map<String, Double> out = new HashMap<>();
            for (String s : symbols) if (prices.containsKey(s)) out.put(s, prices.get(s));
            return out;
        }
    }

    static final class MemoryStore implements LiveStore {
        final Map<String, LiveSlotEntity> slots = new HashMap<>();
        final List<LiveTradeEntity> trades = new ArrayList<>();
        final List<LiveOrderEntity> orders = new ArrayList<>();
        final List<LiveEquityEntity> equity = new ArrayList<>();
        final List<LiveStrategyPnlEntity> pnl = new ArrayList<>();
        final List<LiveCycleEntity> cycles = new ArrayList<>();
        private long seq;
        boolean failCommit;

        @Override public LiveCycleEntity saveCycle(LiveCycleEntity c) {
            if (c.getId() == null) { c.setId(++seq); cycles.add(c); }
            return c;
        }
        @Override public Map<String, LiveSlotEntity> slots() { return new HashMap<>(slots); }
        @Override public void commit(List<LiveSlotEntity> s, List<LiveTradeEntity> t) {
            if (failCommit) throw new IllegalStateException("db down");
            for (LiveSlotEntity x : s) slots.put(LiveStore.key(x.getStrategy(), x.getSymbol()), x);
            trades.addAll(t);
        }
        @Override public LiveOrderEntity saveOrder(LiveOrderEntity o) { if (!orders.contains(o)) orders.add(o); return o; }
        @Override public void saveEquity(LiveEquityEntity e) { equity.add(e); }
        @Override public void saveStrategyPnl(List<LiveStrategyPnlEntity> rows) { pnl.addAll(rows); }
        @Override public void pruneCurves(Instant before) {}

        LiveCycleEntity lastCycle() { return cycles.get(cycles.size() - 1); }
        List<LiveOrderEntity> ordersWith(String status) { return orders.stream().filter(o -> o.getStatus().equals(status)).toList(); }
    }

    /** A strategy whose signal is looked up by name at evaluation time, so a test can change it between cycles. */
    static TradingStrategy constant(String name, Map<String, Double> signals) {
        return new TradingStrategy() {
            public String name() { return name; }
            public String category() { return "test"; }
            public String direction() { return "long_short"; }
            public String description() { return "test"; }
            public Map<String, Double> defaultParams() { return Map.of(); }
            public double[] generateSignals(BarSeries b, Map<String, Double> params) {
                Double v = signals.get(name);
                if (v == null) throw new IllegalStateException(name + " is broken");
                double[] out = new double[b.size()];
                Arrays.fill(out, v);
                return out;
            }
        };
    }
}
