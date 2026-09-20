package com.quant.finance.decision.live;

import com.quant.finance.decision.domain.*;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.live.AlpacaModels.*;
import com.quant.finance.decision.live.LiveDataSource.Base;
import com.quant.finance.decision.live.OrderPlanner.SymbolPlan;
import com.quant.finance.decision.live.SlotLedger.ClosedTrade;
import com.quant.finance.decision.live.SlotLedger.Params;
import com.quant.finance.decision.live.SlotLedger.Transition;
import com.quant.finance.decision.service.StrategyService;
import com.quant.finance.decision.service.UniverseService;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * One cycle of the paper-trading job. For every strategy and symbol it reads the strategy's current signal from
 * fresh bars, updates that strategy's <em>virtual</em> position, adds the virtual positions up per symbol, and sends
 * the difference from the account's real position to Alpaca as one market order per symbol. A strategy's virtual
 * position and P&amp;L change only when the order behind them filled, so the ledger and the account never disagree
 * for long; whatever a cycle could not do is picked up by the next one.
 *
 * <p>Safety: paper endpoint only (checked every cycle), a dry-run mode that changes nothing, caps on gross
 * exposure and orders per cycle, and orders that are cancelled if they do not fill in time.
 */
@Slf4j
public class LiveTradingEngine {

    public enum Trigger { SCHEDULED, MANUAL }

    public enum Mode { NORMAL, FLATTEN }

    /** Client order ids of this job start with this, so leftovers can be told apart from anyone else's orders. */
    static final String ORDER_PREFIX = "qe-";
    private static final Duration ASSET_TTL = Duration.ofHours(6);

    /** Pauses between polls of an order; replaceable so tests do not wait. */
    interface Sleeper { void sleep(long millis); }

    private final TradingGateway gateway;
    private final LiveDataSource data;
    private final StrategyService strategies;
    private final UniverseService universe;
    private final LiveStore store;
    private final Clock clock;
    private final Executor executor;
    private final Sleeper sleeper;
    private final Map<String, CachedAsset> assets = new HashMap<>();

    private record CachedAsset(AssetInfo info, Instant at) {}

    public LiveTradingEngine(TradingGateway gateway, LiveDataSource data, StrategyService strategies,
                             UniverseService universe, LiveStore store, Clock clock, Executor executor) {
        this(gateway, data, strategies, universe, store, clock, executor, LiveTradingEngine::realSleep);
    }

    LiveTradingEngine(TradingGateway gateway, LiveDataSource data, StrategyService strategies,
                      UniverseService universe, LiveStore store, Clock clock, Executor executor, Sleeper sleeper) {
        this.gateway = gateway;
        this.data = data;
        this.strategies = strategies;
        this.universe = universe;
        this.store = store;
        this.clock = clock;
        this.executor = executor;
        this.sleeper = sleeper;
    }

    private static void realSleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ---- a cycle -------------------------------------------------------------------------------

    /** Runs one cycle and returns its stored record. Never throws: failures are recorded on the cycle. */
    public LiveCycleEntity runCycle(LiveSettings cfg, Trigger trigger, Mode mode) {
        Instant started = clock.instant();
        LiveCycleEntity cycle = new LiveCycleEntity();
        cycle.setStartedAt(started);
        cycle.setStatus("RUNNING");
        cycle.setMode(mode.name());
        cycle.setTriggeredBy(trigger.name());
        cycle.setDryRun(cfg.dryRun());
        cycle = store.saveCycle(cycle);

        Stats st = new Stats();
        try {
            String skipped = execute(cfg, mode, cycle, st);
            cycle.setStatus(skipped != null ? "SKIPPED" : "COMPLETED");
            cycle.setMessage(skipped != null ? skipped : st.summary(cfg.dryRun()));
        } catch (Exception e) {
            log.error("Paper trading cycle #{} failed", cycle.getId(), e);
            st.errors++;
            cycle.setStatus("FAILED");
            cycle.setMessage(describe(e));
        }
        cycle.setSymbols(st.symbols);
        cycle.setStrategies(st.strategies);
        cycle.setSignals(st.signals);
        cycle.setOrdersPlanned(st.ordersPlanned);
        cycle.setOrdersFilled(st.ordersFilled);
        cycle.setOrdersFailed(st.ordersFailed);
        cycle.setTradesClosed(st.tradesClosed);
        cycle.setErrors(st.errors);
        Instant finished = clock.instant();
        cycle.setFinishedAt(finished);
        cycle.setDurationMs(Duration.between(started, finished).toMillis());
        cycle = store.saveCycle(cycle);
        log.info("Paper trading cycle #{} {} in {} ms: {}", cycle.getId(), cycle.getStatus(), cycle.getDurationMs(), cycle.getMessage());
        return cycle;
    }

    private static final class Stats {
        int symbols, strategies, signals, ordersPlanned, ordersFilled, ordersFailed, tradesClosed, errors;
        final List<String> notes = new ArrayList<>();

        String summary(boolean dryRun) {
            String s = signals + " signals, " + ordersPlanned + " order" + (ordersPlanned == 1 ? "" : "s")
                    + (dryRun ? " planned (dry run: none sent)" : " (" + ordersFilled + " filled, " + ordersFailed + " failed)")
                    + ", " + tradesClosed + " trade" + (tradesClosed == 1 ? "" : "s") + " closed";
            if (errors > 0) s += ", " + errors + " error" + (errors == 1 ? "" : "s");
            return notes.isEmpty() ? s : s + ". " + String.join("; ", notes.subList(0, Math.min(5, notes.size())));
        }
    }

    /** @return a reason when the cycle did nothing on purpose (e.g. market closed), else null */
    private String execute(LiveSettings cfg, Mode mode, LiveCycleEntity cycle, Stats st) {
        if (!gateway.isPaper())
            throw new IllegalStateException("Refusing to trade: the configured Alpaca endpoint is not the paper-trading one.");
        AccountInfo account = gateway.account();
        if (account.tradingBlocked() || account.accountBlocked())
            throw new IllegalStateException("The Alpaca account is blocked from trading (status " + account.status() + ").");
        MarketClock market = gateway.clock();
        Instant now = clock.instant();
        if (cfg.marketHoursOnly() && !market.open()) {
            saveEquity(account, cycle, now);
            return "Market closed" + (market.nextOpen() != null ? "; next open " + market.nextOpen() : "");
        }
        if (!cfg.dryRun()) cancelLeftoverOrders();

        // ---- scope ----
        Map<String, TradingStrategy> strats = mode == Mode.FLATTEN ? Map.of()
                : cfg.strategyNames().isEmpty() ? strategies.getEnabledStrategies() : strategies.getStrategies(cfg.strategyNames());
        List<String> scopeSymbols = mode == Mode.FLATTEN ? List.of()
                : cfg.symbols().isEmpty() ? universe.get() : cfg.symbols();
        Map<String, LiveSlotEntity> slots = store.slots();
        Set<String> symbols = new TreeSet<>(scopeSymbols);
        slots.values().stream().filter(s -> s.getDirection() != 0).forEach(s -> symbols.add(s.getSymbol()));
        st.symbols = scopeSymbols.size();
        st.strategies = strats.size();
        if (symbols.isEmpty()) { saveEquity(account, cycle, now); return "Nothing to do: no symbols in scope and no open positions."; }

        Map<String, Double> actual = new HashMap<>();
        for (PositionInfo p : gateway.positions()) if (symbols.contains(p.symbol())) actual.put(p.symbol(), p.qty());
        Map<String, Double> prices = data.latestPrices(symbols);
        Set<String> tradable = new HashSet<>();
        Map<String, Boolean> shortable = new HashMap<>();
        for (String sym : symbols) {
            if (!prices.containsKey(sym)) { st.errors++; st.notes.add("no price for " + sym); continue; }
            try {
                AssetInfo a = asset(sym, now);
                if (!a.tradable()) { st.errors++; st.notes.add(sym + " is not tradable"); continue; }
                tradable.add(sym);
                shortable.put(sym, a.shortable());
            } catch (AlpacaApiException e) {
                st.errors++; st.notes.add("asset " + sym + ": " + e.getMessage());
            }
        }

        // ---- signals ----
        Map<String, Double> signals = mode == Mode.FLATTEN ? Map.of()
                : evaluateSignals(cfg, strats, scopeSymbols.stream().filter(tradable::contains).toList(), now, st);

        // ---- virtual positions ----
        List<Pending> pending = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (TradingStrategy s : strats.values())
            for (String sym : scopeSymbols) if (tradable.contains(sym)) keys.add(LiveStore.key(s.name(), sym));
        slots.forEach((k, s) -> { if (s.getDirection() != 0 && tradable.contains(s.getSymbol())) keys.add(k); });
        Map<String, Double> virtual = new HashMap<>();
        slots.values().forEach(s -> { if (symbols.contains(s.getSymbol())) virtual.merge(s.getSymbol(), signed(s), Double::sum); });
        for (String sym : symbols) if (tradable.contains(sym)) virtual.putIfAbsent(sym, 0.0);
        virtual.keySet().retainAll(tradable);

        for (String key : keys) {
            int bar = key.indexOf('|');
            String strategy = key.substring(0, bar), symbol = key.substring(bar + 1);
            boolean inScope = strats.containsKey(strategy) && scopeSymbols.contains(symbol) && mode == Mode.NORMAL;
            String forced = mode == Mode.FLATTEN ? SlotLedger.FLATTEN : !inScope ? SlotLedger.REMOVED : null;
            Double signal = forced != null ? Double.valueOf(0) : signals.get(key);
            if (signal == null) continue;                                    // could not be evaluated this cycle
            LiveSlotEntity slot = slots.get(key);
            SlotLedger.State before = slot == null ? SlotLedger.State.FLAT : stateOf(slot);
            Params params = paramsFor(cfg, strategy, shortable.getOrDefault(symbol, false));
            double price = prices.get(symbol);
            Transition tr = SlotLedger.apply(before, signal, price, price, now, params, forced);
            pending.add(new Pending(strategy, symbol, slot, before, signal, params, forced, tr));
            virtual.merge(symbol, tr.qtyDelta(), Double::sum);
        }

        // ---- plan ----
        OrderPlanner.Plan plan = OrderPlanner.plan(virtual, actual, prices, cfg.maxGrossUsd(), cfg.maxOrdersPerCycle());
        Set<String> heldBack = new HashSet<>();
        for (OrderPlanner.Skipped sk : plan.skipped()) {
            heldBack.add(sk.symbol());
            st.notes.add(sk.symbol() + " held back (" + sk.reason().toLowerCase().replace('_', ' ') + ")");
            saveOrderRow(cycle, sk.symbol(), "-", 0, "SKIPPED", null, cfg.dryRun(), "Held back: " + sk.reason(), null, null);
        }
        for (SymbolPlan sp : plan.withOrders()) st.ordersPlanned += sp.orders().size();

        if (cfg.dryRun()) {
            for (SymbolPlan sp : plan.withOrders())
                for (OrderPlanner.Order o : sp.orders())
                    saveOrderRow(cycle, o.symbol(), o.side().toUpperCase(), o.qty(), "DRY_RUN", null, true,
                            reasonOf(sp, pending), (double) sp.targetQty(), sp.positionBefore());
            saveEquity(account, cycle, now);
            return null;
        }

        // ---- execute (orders that reduce exposure first) ----
        Map<String, Double> fills = new HashMap<>();          // symbol -> average fill price of a fully filled plan
        Set<String> failed = new HashSet<>();
        List<SymbolPlan> toRun = new ArrayList<>(plan.withOrders());
        toRun.sort(Comparator.comparing((SymbolPlan p) -> !p.orders().get(0).reducesExposure()).thenComparing(SymbolPlan::symbol));
        for (SymbolPlan sp : toRun) {
            Double fill = executePlan(cfg, cycle, sp, reasonOf(sp, pending), st);
            if (fill == null) failed.add(sp.symbol()); else fills.put(sp.symbol(), fill);
        }

        // ---- commit the virtual positions whose orders filled (or that needed none) ----
        Map<String, List<LiveSlotEntity>> toSave = new LinkedHashMap<>();
        Map<String, List<LiveTradeEntity>> closedBySymbol = new LinkedHashMap<>();
        Set<String> committed = new HashSet<>();
        for (String sym : tradable) {
            if (heldBack.contains(sym) || failed.contains(sym)) continue;
            committed.add(sym);
        }
        for (Pending p : pending) {
            if (!committed.contains(p.symbol)) continue;
            double price = prices.get(p.symbol);
            double exec = fills.getOrDefault(p.symbol, price);
            Transition tr = SlotLedger.apply(p.before, p.signal, price, exec, now, p.params, p.forced);
            LiveSlotEntity slot = p.slot != null ? p.slot : newSlot(p.strategy, p.symbol);
            applyState(slot, tr.next(), p.signal, price, now);
            slots.put(LiveStore.key(p.strategy, p.symbol), slot);
            toSave.computeIfAbsent(p.symbol, k -> new ArrayList<>()).add(slot);
            for (ClosedTrade c : tr.closed())
                closedBySymbol.computeIfAbsent(p.symbol, k -> new ArrayList<>()).add(tradeRow(p.strategy, p.symbol, c, cycle.getId()));
            st.tradesClosed += tr.closed().size();
        }
        for (LiveSlotEntity s : slots.values()) {                        // keep open positions marked to market
            Double price = prices.get(s.getSymbol());
            if (s.getDirection() == 0 || price == null) continue;
            List<LiveSlotEntity> list = toSave.computeIfAbsent(s.getSymbol(), k -> new ArrayList<>());
            if (!list.contains(s)) { s.setLastPrice(price); s.setUpdatedAt(now); list.add(s); }
        }
        for (Map.Entry<String, List<LiveSlotEntity>> e : toSave.entrySet()) {
            try {
                store.commit(e.getValue(), closedBySymbol.getOrDefault(e.getKey(), List.of()));
            } catch (RuntimeException ex) {
                st.errors++;
                st.notes.add("could not save " + e.getKey() + ": " + ex.getMessage());
                log.error("Paper trading: could not commit {}", e.getKey(), ex);
            }
        }

        saveStrategyPnl(slots, cycle, now);
        saveEquity(st.ordersFilled > 0 ? safeAccount(account) : account, cycle, now);
        store.pruneCurves(now.minus(Duration.ofDays(30)));
        return null;
    }

    // ---- signals ---------------------------------------------------------------------------------

    private Map<String, Double> evaluateSignals(LiveSettings cfg, Map<String, TradingStrategy> strats,
                                                List<String> symbols, Instant now, Stats st) {
        Map<String, Timeframe> frames = new HashMap<>();
        Map<Base, Integer> lookback = new EnumMap<>(Base.class);
        for (String name : strats.keySet()) {
            Timeframe tf = SignalEvaluator.liveFrame(Timeframe.isAuto(cfg.timeframeMode())
                    ? strategies.recommendedTimeframe(name) : Timeframe.from(cfg.timeframeMode()));
            frames.put(name, tf);
            lookback.merge(SignalEvaluator.baseFor(tf), SignalEvaluator.lookbackDays(tf, cfg.lookbackBars()), Math::max);
        }
        Map<String, Map<String, Double>> paramsByStrategy = new HashMap<>();
        for (String name : strats.keySet()) paramsByStrategy.put(name, strategies.getParams(name));

        Map<String, Double> out = new HashMap<>();
        for (String symbol : symbols) {
            Map<Base, BarSeries> bars = new EnumMap<>(Base.class);
            for (Map.Entry<Base, Integer> b : lookback.entrySet()) {
                try {
                    bars.put(b.getKey(), data.bars(symbol, b.getKey(), b.getValue()));
                } catch (RuntimeException e) {
                    st.errors++;
                    st.notes.add("bars " + symbol + ": " + describe(e));
                    log.warn("Paper trading: no {} bars for {}: {}", b.getKey(), symbol, e.getMessage());
                }
            }
            Map<String, CompletableFuture<Double>> futures = new LinkedHashMap<>();
            for (TradingStrategy s : strats.values()) {
                BarSeries base = bars.get(SignalEvaluator.baseFor(frames.get(s.name())));
                if (base == null) continue;
                futures.put(s.name(), CompletableFuture.supplyAsync(
                        () -> SignalEvaluator.lastSignal(s, paramsByStrategy.get(s.name()), base, frames.get(s.name()), now), executor));
            }
            for (Map.Entry<String, CompletableFuture<Double>> f : futures.entrySet()) {
                try {
                    Double sig = f.getValue().join();
                    if (sig != null) { out.put(LiveStore.key(f.getKey(), symbol), sig); st.signals++; }
                } catch (CompletionException e) {
                    st.errors++;
                    log.warn("Paper trading: {} on {} failed: {}", f.getKey(), symbol, e.getCause() == null ? e : e.getCause().toString());
                }
            }
        }
        return out;
    }

    private Params paramsFor(LiveSettings cfg, String strategy, boolean shortable) {
        double sl = 0, tp = 0;
        if (cfg.useRiskDefaults() && strategies.isStrategy(strategy)) {
            Double a = strategies.defaultStopLossPct(strategy), b = strategies.defaultTakeProfitPct(strategy);
            sl = a == null ? 0 : a;
            tp = b == null ? 0 : b;
        }
        return new Params(cfg.allocationUsd(), cfg.positionSize(), cfg.allowShort() && shortable, sl, tp);
    }

    // ---- orders ----------------------------------------------------------------------------------

    /** @return the average fill price when every order of the plan filled, else null */
    private Double executePlan(LiveSettings cfg, LiveCycleEntity cycle, SymbolPlan sp, String reason, Stats st) {
        double filledQty = 0, filledValue = 0;
        int leg = 0;
        for (OrderPlanner.Order o : sp.orders()) {
            LiveOrderEntity row = newOrderRow(cycle, o.symbol(), o.side().toUpperCase(), o.qty(), false, reason,
                    (double) sp.targetQty(), sp.positionBefore());
            row.setClientOrderId(ORDER_PREFIX + cycle.getId() + "-" + o.symbol() + "-" + leg++);
            OrderInfo result = null;
            try {
                result = submitAndWait(cfg, o, row);
            } catch (RuntimeException e) {
                row.setStatus(e instanceof AlpacaApiException ae && ae.status() >= 400 && ae.status() < 500 ? "REJECTED" : "FAILED");
                row.setError(describe(e));
                log.warn("Paper trading: {} {} x{} failed: {}", o.side(), o.symbol(), o.qty(), e.getMessage());
            }
            if (result != null) {
                row.setAlpacaOrderId(result.id());
                row.setFilledQty(result.filledQty());
                row.setFilledAvgPrice(result.filledAvgPrice());
                row.setFilledAt(result.filledAt());
                boolean full = result.isFilled() && result.filledQty() >= o.qty() - 1e-9;
                row.setStatus(full ? "FILLED" : result.filledQty() > 0 ? "PARTIAL" : result.status().equals("rejected") ? "REJECTED" : "CANCELED");
                if (!full && row.getError() == null) row.setError("Order ended " + result.status() + " with " + result.filledQty() + " of " + o.qty() + " filled");
                if (result.filledQty() > 0 && result.filledAvgPrice() != null) {
                    filledQty += result.filledQty();
                    filledValue += result.filledQty() * result.filledAvgPrice();
                }
            }
            store.saveOrder(row);
            if (!"FILLED".equals(row.getStatus())) {
                st.ordersFailed++;
                st.notes.add(o.symbol() + " " + o.side() + " " + o.qty() + ": " + row.getStatus().toLowerCase()
                        + (row.getError() != null ? " (" + row.getError() + ")" : ""));
                return null;                                              // do not send the second leg of a move through zero
            }
            st.ordersFilled++;
        }
        return filledQty > 0 ? filledValue / filledQty : null;
    }

    private OrderInfo submitAndWait(LiveSettings cfg, OrderPlanner.Order o, LiveOrderEntity row) {
        row.setSubmittedAt(clock.instant());
        OrderInfo order;
        try {
            order = gateway.submitMarketOrder(o.symbol(), o.qty(), o.side(), row.getClientOrderId());
        } catch (AlpacaApiException e) {
            if (e.status() != 0) throw e;                                   // an answer: rejected
            // no answer: the order may or may not exist — look before deciding it failed
            order = gateway.findByClientOrderId(row.getClientOrderId()).orElseThrow(() -> e);
        }
        Instant deadline = clock.instant().plusSeconds(cfg.fillTimeoutSeconds());
        while (!order.isTerminal() && clock.instant().isBefore(deadline)) {
            sleeper.sleep(500);
            order = gateway.order(order.id());
        }
        if (!order.isTerminal()) {
            gateway.cancelOrder(order.id());
            sleeper.sleep(500);
            order = gateway.order(order.id());
            row.setError("Not filled within " + cfg.fillTimeoutSeconds() + " s; cancelled");
        }
        return order;
    }

    private void cancelLeftoverOrders() {
        try {
            for (OrderInfo o : gateway.openOrders())
                if (o.clientOrderId() != null && o.clientOrderId().startsWith(ORDER_PREFIX)) {
                    log.warn("Paper trading: cancelling leftover order {} ({} {} {})", o.id(), o.side(), o.symbol(), o.qty());
                    gateway.cancelOrder(o.id());
                }
        } catch (RuntimeException e) {
            log.warn("Paper trading: could not check for leftover orders: {}", e.getMessage());
        }
    }

    private AssetInfo asset(String symbol, Instant now) {
        CachedAsset c = assets.get(symbol);
        if (c != null && Duration.between(c.at(), now).compareTo(ASSET_TTL) < 0) return c.info();
        AssetInfo a = gateway.asset(symbol);
        assets.put(symbol, new CachedAsset(a, now));
        return a;
    }

    // ---- records ---------------------------------------------------------------------------------

    private record Pending(String strategy, String symbol, LiveSlotEntity slot, SlotLedger.State before,
                           double signal, Params params, String forced, Transition tr) {}

    private static double signed(LiveSlotEntity s) { return s.getDirection() * s.getQty(); }

    private static SlotLedger.State stateOf(LiveSlotEntity s) {
        return new SlotLedger.State(s.getDirection(), s.getQty(), s.getEntryPrice() == null ? 0 : s.getEntryPrice(),
                s.getEntryTime(), s.getBlockedDir(), s.getRealizedPnl());
    }

    private static LiveSlotEntity newSlot(String strategy, String symbol) {
        LiveSlotEntity s = new LiveSlotEntity();
        s.setStrategy(strategy);
        s.setSymbol(symbol);
        return s;
    }

    private static void applyState(LiveSlotEntity slot, SlotLedger.State st, double signal, double price, Instant now) {
        slot.setDirection(st.direction());
        slot.setQty(st.qty());
        slot.setEntryPrice(st.direction() == 0 ? null : st.entryPrice());
        slot.setEntryTime(st.direction() == 0 ? null : st.entryTime());
        slot.setBlockedDir(st.blockedDir());
        slot.setRealizedPnl(st.realizedPnl());
        slot.setLastSignal(signal);
        slot.setLastSignalAt(now);
        slot.setLastPrice(price);
        slot.setUpdatedAt(now);
    }

    private static LiveTradeEntity tradeRow(String strategy, String symbol, ClosedTrade c, Long cycleId) {
        LiveTradeEntity t = new LiveTradeEntity();
        t.setStrategy(strategy);
        t.setSymbol(symbol);
        t.setSide(c.side());
        t.setQty(c.qty());
        t.setEntryTime(c.entryTime());
        t.setEntryPrice(c.entryPrice());
        t.setExitTime(c.exitTime());
        t.setExitPrice(c.exitPrice());
        t.setPnlUsd(c.pnlUsd());
        t.setReturnPct(c.returnPct());
        t.setExitReason(c.reason());
        t.setCycleId(cycleId);
        return t;
    }

    private static String reasonOf(SymbolPlan sp, List<Pending> pending) {
        long moving = pending.stream().filter(p -> p.symbol.equals(sp.symbol()) && p.tr.qtyDelta() != 0).count();
        return moving == 0 ? "Bringing the account to the strategies' net position"
                : moving + " strateg" + (moving == 1 ? "y" : "ies") + " changed position; net " + String.format("%.2f", sp.virtualQty()) + " shares";
    }

    private LiveOrderEntity newOrderRow(LiveCycleEntity cycle, String symbol, String side, double qty, boolean dryRun,
                                        String reason, Double target, Double before) {
        LiveOrderEntity o = new LiveOrderEntity();
        o.setCycleId(cycle.getId());
        o.setSymbol(symbol);
        o.setSide(side);
        o.setQty(qty);
        o.setStatus("PENDING");
        o.setDryRun(dryRun);
        o.setReason(reason);
        o.setTargetQty(target);
        o.setPositionBefore(before);
        return o;
    }

    private void saveOrderRow(LiveCycleEntity cycle, String symbol, String side, double qty, String status,
                              String error, boolean dryRun, String reason, Double target, Double before) {
        LiveOrderEntity o = newOrderRow(cycle, symbol, side, qty, dryRun, reason, target, before);
        o.setStatus(status);
        o.setError(error);
        o.setSubmittedAt(clock.instant());
        store.saveOrder(o);
    }

    private void saveStrategyPnl(Map<String, LiveSlotEntity> slots, LiveCycleEntity cycle, Instant now) {
        Map<String, double[]> per = new TreeMap<>();                     // strategy -> realized, unrealized, open
        for (LiveSlotEntity s : slots.values()) {
            double[] a = per.computeIfAbsent(s.getStrategy(), k -> new double[3]);
            a[0] += s.getRealizedPnl();
            if (s.getDirection() != 0 && s.getLastPrice() != null && s.getEntryPrice() != null) {
                a[1] += s.getDirection() * s.getQty() * (s.getLastPrice() - s.getEntryPrice());
                a[2]++;
            }
        }
        List<LiveStrategyPnlEntity> rows = new ArrayList<>();
        per.forEach((name, a) -> {
            LiveStrategyPnlEntity r = new LiveStrategyPnlEntity();
            r.setCycleId(cycle.getId());
            r.setTs(now);
            r.setStrategy(name);
            r.setRealized(a[0]);
            r.setUnrealized(a[1]);
            r.setOpenSlots((int) a[2]);
            rows.add(r);
        });
        if (!rows.isEmpty()) store.saveStrategyPnl(rows);
    }

    private AccountInfo safeAccount(AccountInfo fallback) {
        try { return gateway.account(); } catch (RuntimeException e) { return fallback; }
    }

    private void saveEquity(AccountInfo a, LiveCycleEntity cycle, Instant now) {
        LiveEquityEntity e = new LiveEquityEntity();
        e.setTs(now);
        e.setEquity(a.equity());
        e.setCash(a.cash());
        e.setBuyingPower(a.buyingPower());
        e.setLongValue(a.longMarketValue());
        e.setShortValue(a.shortMarketValue());
        e.setCycleId(cycle.getId());
        store.saveEquity(e);
    }

    private static String describe(Throwable e) {
        Throwable t = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
