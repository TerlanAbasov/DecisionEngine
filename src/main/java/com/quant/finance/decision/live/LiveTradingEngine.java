package com.quant.finance.decision.live;

import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.entity.LiveCycleEntity;
import com.quant.finance.decision.entity.LiveSlotEntity;
import com.quant.finance.decision.entity.LiveStrategyPnlEntity;
import com.quant.finance.decision.entity.LiveTradeEntity;
import com.quant.finance.decision.error.AlpacaApiException;
import com.quant.finance.decision.live.AlpacaModels.AccountInfo;
import com.quant.finance.decision.live.AlpacaModels.AssetInfo;
import com.quant.finance.decision.live.AlpacaModels.MarketClock;
import com.quant.finance.decision.live.AlpacaModels.PositionInfo;
import com.quant.finance.decision.live.LiveDataSource.Base;
import com.quant.finance.decision.live.OrderExecutor.Sleeper;
import com.quant.finance.decision.live.OrderPlanner.Plan;
import com.quant.finance.decision.live.OrderPlanner.Skipped;
import com.quant.finance.decision.live.OrderPlanner.SymbolPlan;
import com.quant.finance.decision.live.SlotLedger.ClosedTrade;
import com.quant.finance.decision.live.SlotLedger.Params;
import com.quant.finance.decision.live.SlotLedger.State;
import com.quant.finance.decision.live.SlotLedger.Transition;
import com.quant.finance.decision.service.StrategyService;
import com.quant.finance.decision.service.UniverseService;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * One cycle of the paper-trading job: each strategy's signal updates its <em>virtual</em> position, the positions are summed per symbol and the difference from the account is ordered.
 * A virtual position changes only when its order filled. Safety: paper only, dry run, exposure and order caps, orders cancelled if unfilled in time.
 */
@Slf4j
public class LiveTradingEngine {

    public enum Trigger { SCHEDULED, MANUAL }

    public enum Mode { NORMAL, FLATTEN }

    private enum CycleStatus { RUNNING, COMPLETED, SKIPPED, FAILED }

    private static final Duration ASSET_TTL = Duration.ofHours(6);
    private static final Duration CURVE_RETENTION = Duration.ofDays(30);

    private final TradingGateway gateway;
    private final LiveDataSource data;
    private final StrategyService strategies;
    private final UniverseService universe;
    private final LiveStore store;
    private final Clock clock;
    private final Executor executor;
    private final OrderExecutor orders;
    private final Map<String, CachedAsset> assets = new HashMap<>();

    public LiveTradingEngine(TradingGateway gateway, LiveDataSource data, StrategyService strategies,
                             UniverseService universe, LiveStore store, Clock clock, Executor executor) {
        this(gateway, data, strategies, universe, store, clock, executor, LiveTradingEngine::sleep);
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
        this.orders = new OrderExecutor(gateway, store, clock, sleeper);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ---- values passed between the steps of a cycle --------------------------------------------

    /** What this cycle is doing and where it records what it did. */
    private record Run(LiveSettings cfg, Mode mode, LiveCycleEntity cycle, CycleStats stats, Instant now) {}

    /** What is in play: the strategies and symbols the settings ask for, plus every symbol with an open position. */
    private record Scope(Map<String, TradingStrategy> strategies, List<String> symbols, Set<String> allSymbols) {}

    /** Latest prices, and the assets that may be traded. */
    private record Market(Map<String, Double> prices, Map<String, AssetInfo> tradable) {
        boolean isTradable(String symbol) { return tradable.containsKey(symbol); }
        double price(String symbol) { return prices.get(symbol); }
        boolean isShortable(String symbol) { return tradable.get(symbol).shortable(); }
    }

    private record SlotId(String strategy, String symbol) {
        String key() { return LiveStore.key(strategy, symbol); }
    }

    /** A strategy's virtual position after this cycle's signal, worked out at the last price; redone at the fill price once the order filled. */
    private record Move(SlotId id, LiveSlotEntity slot, State before, double signal, double price, Params params,
                        String forcedExit, Transition planned) {

        static Move decide(SlotId id, LiveSlotEntity slot, double signal, double price, Params params, String forcedExit, Instant now) {
            State before = slot == null ? State.FLAT : LiveRows.stateOf(slot);
            Transition planned = SlotLedger.apply(before, signal, price, price, now, params, forcedExit);
            return new Move(id, slot, before, signal, price, params, forcedExit, planned);
        }

        String symbol() { return id.symbol(); }

        Transition filledAt(double fillPrice, Instant now) {
            return SlotLedger.apply(before, signal, price, fillPrice, now, params, forcedExit);
        }
    }

    /** Slots and closed trades of one symbol, saved together. */
    private record Commit(Set<LiveSlotEntity> slots, List<LiveTradeEntity> trades) {
        Commit() { this(new LinkedHashSet<>(), new ArrayList<>()); }
    }

    private record CachedAsset(AssetInfo info, Instant at) {}

    // ---- a cycle -------------------------------------------------------------------------------

    /** Runs one cycle and returns its stored record. Never throws: failures are recorded on the cycle. */
    public LiveCycleEntity runCycle(LiveSettings cfg, Trigger trigger, Mode mode) {
        Instant started = clock.instant();
        LiveCycleEntity cycle = new LiveCycleEntity();
        cycle.setStartedAt(started);
        cycle.setStatus(CycleStatus.RUNNING.name());
        cycle.setMode(mode.name());
        cycle.setTriggeredBy(trigger.name());
        cycle.setDryRun(cfg.dryRun());
        cycle = store.saveCycle(cycle);

        CycleStats stats = new CycleStats();
        try {
            String skipReason = execute(cfg, mode, cycle, stats);
            cycle.setStatus((skipReason != null ? CycleStatus.SKIPPED : CycleStatus.COMPLETED).name());
            cycle.setMessage(skipReason != null ? skipReason : stats.summary(cfg.dryRun()));
        } catch (Exception e) {
            log.error("Paper trading cycle #{} failed", cycle.getId(), e);
            stats.errors++;
            cycle.setStatus(CycleStatus.FAILED.name());
            cycle.setMessage(CycleStats.describe(e));
        }
        stats.writeTo(cycle);
        Instant finished = clock.instant();
        cycle.setFinishedAt(finished);
        cycle.setDurationMs(Duration.between(started, finished).toMillis());
        cycle = store.saveCycle(cycle);
        log.info("Paper trading cycle #{} {} in {} ms: {}", cycle.getId(), cycle.getStatus(), cycle.getDurationMs(), cycle.getMessage());
        return cycle;
    }

    /** Runs the steps in order; returns why the cycle did nothing on purpose (e.g. market closed), else null. */
    private String execute(LiveSettings cfg, Mode mode, LiveCycleEntity cycle, CycleStats stats) {
        AccountInfo account = requireTradableAccount();
        MarketClock marketClock = gateway.clock();
        Run run = new Run(cfg, mode, cycle, stats, clock.instant());

        if (cfg.marketHoursOnly() && !marketClock.open())
            return skip(run, account, "Market closed" + (marketClock.nextOpen() != null ? "; next open " + marketClock.nextOpen() : ""));
        if (!cfg.dryRun()) orders.cancelLeftovers();

        Map<String, LiveSlotEntity> slots = store.slots();
        Scope scope = scopeOf(run, slots);
        stats.symbols = scope.symbols().size();
        stats.strategies = scope.strategies().size();
        if (scope.allSymbols().isEmpty())
            return skip(run, account, "Nothing to do: no symbols in scope and no open positions.");

        Map<String, Double> actual = accountPositions(scope.allSymbols());
        Market market = quote(run, scope.allSymbols());
        Map<String, Double> signals = mode == Mode.FLATTEN ? Map.of() : evaluateSignals(run, scope, market);
        List<Move> moves = decideMoves(run, scope, market, signals, slots);

        Plan plan = OrderPlanner.plan(netVirtualQty(moves, slots, market), actual, market.prices(),
                cfg.maxGrossUsd(), cfg.maxOrdersPerCycle());
        stats.ordersPlanned = plan.orderCount();
        Set<String> unsettled = recordHeldBack(run, plan);

        if (cfg.dryRun()) {
            plan.withOrders().forEach(sp -> orders.recordDryRun(cycle, sp, reasonOf(sp, moves)));
            saveEquity(run, account);
            return null;
        }

        Map<String, Double> fills = sendOrders(run, plan, moves, unsettled);
        settle(run, slots, moves, market, fills, unsettled);
        saveStrategyPnl(run, slots);
        saveEquity(run, stats.ordersFilled > 0 ? refreshed(account) : account);
        store.pruneCurves(run.now().minus(CURVE_RETENTION));
        return null;
    }

    private String skip(Run run, AccountInfo account, String reason) {
        saveEquity(run, account);
        return reason;
    }

    private AccountInfo requireTradableAccount() {
        if (!gateway.isPaper())
            throw new IllegalStateException("Refusing to trade: the configured Alpaca endpoint is not the paper-trading one.");
        AccountInfo account = gateway.account();
        if (account.tradingBlocked() || account.accountBlocked())
            throw new IllegalStateException("The Alpaca account is blocked from trading (status " + account.status() + ").");
        return account;
    }

    // ---- what is in play -----------------------------------------------------------------------

    /** A flatten cycle has no strategies or symbols in scope: it only closes what is open. */
    private Scope scopeOf(Run run, Map<String, LiveSlotEntity> slots) {
        boolean flatten = run.mode() == Mode.FLATTEN;
        Map<String, TradingStrategy> strats = flatten ? Map.of() : run.cfg().strategiesIn(strategies);
        List<String> symbols = flatten ? List.of() : run.cfg().symbolsIn(universe);

        Set<String> all = new TreeSet<>(symbols);
        for (LiveSlotEntity s : slots.values()) if (LiveRows.isOpen(s)) all.add(s.getSymbol());
        return new Scope(strats, symbols, all);
    }

    /** The account's signed share count for each of {@code symbols} it holds. */
    private Map<String, Double> accountPositions(Set<String> symbols) {
        Map<String, Double> actual = new HashMap<>();
        for (PositionInfo p : gateway.positions()) if (symbols.contains(p.symbol())) actual.put(p.symbol(), p.qty());
        return actual;
    }

    /** Fetches prices and asset details; a symbol with no price or that is not tradable is left out and noted. */
    private Market quote(Run run, Set<String> symbols) {
        Map<String, Double> prices = data.latestPrices(symbols);
        Map<String, AssetInfo> tradable = new HashMap<>();
        for (String symbol : symbols) {
            if (!prices.containsKey(symbol)) { run.stats().error("no price for " + symbol); continue; }
            try {
                AssetInfo asset = asset(symbol, run.now());
                if (asset.tradable()) tradable.put(symbol, asset);
                else run.stats().error(symbol + " is not tradable");
            } catch (AlpacaApiException e) {
                run.stats().error("asset " + symbol + ": " + e.getMessage());
            }
        }
        return new Market(prices, tradable);
    }

    private AssetInfo asset(String symbol, Instant now) {
        CachedAsset cached = assets.get(symbol);
        if (cached != null && Duration.between(cached.at(), now).compareTo(ASSET_TTL) < 0) return cached.info();
        AssetInfo fresh = gateway.asset(symbol);
        assets.put(symbol, new CachedAsset(fresh, now));
        return fresh;
    }

    // ---- signals -------------------------------------------------------------------------------

    /** Each strategy's target position per symbol, keyed by {@link LiveStore#key}; one that could not be evaluated is left out. */
    private Map<String, Double> evaluateSignals(Run run, Scope scope, Market market) {
        LiveSettings cfg = run.cfg();
        Map<String, Timeframe> frames = new HashMap<>();
        Map<String, Map<String, Double>> params = new HashMap<>();
        Map<Base, Integer> lookbackDays = new EnumMap<>(Base.class);
        for (String name : scope.strategies().keySet()) {
            Timeframe frame = SignalEvaluator.liveFrame(Timeframe.isAuto(cfg.timeframeMode())
                    ? strategies.recommendedTimeframe(name) : Timeframe.from(cfg.timeframeMode()));
            frames.put(name, frame);
            params.put(name, strategies.getParams(name));
            lookbackDays.merge(SignalEvaluator.baseFor(frame), SignalEvaluator.lookbackDays(frame, cfg.lookbackBars()), Math::max);
        }

        Map<String, Double> signals = new HashMap<>();
        for (String symbol : scope.symbols()) {
            if (!market.isTradable(symbol)) continue;
            Map<Base, BarSeries> bars = fetchBars(run.stats(), symbol, lookbackDays);

            Map<String, CompletableFuture<Double>> running = new LinkedHashMap<>();   // every strategy of the symbol at once
            for (TradingStrategy strategy : scope.strategies().values()) {
                String name = strategy.name();
                BarSeries base = bars.get(SignalEvaluator.baseFor(frames.get(name)));
                if (base == null) continue;
                running.put(name, CompletableFuture.supplyAsync(
                        () -> SignalEvaluator.lastSignal(strategy, params.get(name), base, frames.get(name), run.now()), executor));
            }
            running.forEach((name, future) -> {
                try {
                    Double signal = future.join();
                    if (signal == null) return;
                    signals.put(LiveStore.key(name, symbol), signal);
                    run.stats().signals++;
                } catch (CompletionException e) {
                    run.stats().errors++;
                    log.warn("Paper trading: {} on {} failed: {}", name, symbol, e.getCause() == null ? e : e.getCause().toString());
                }
            });
        }
        return signals;
    }

    private Map<Base, BarSeries> fetchBars(CycleStats stats, String symbol, Map<Base, Integer> lookbackDays) {
        Map<Base, BarSeries> bars = new EnumMap<>(Base.class);
        lookbackDays.forEach((base, days) -> {
            try {
                bars.put(base, data.bars(symbol, base, days));
            } catch (RuntimeException e) {
                stats.error("bars " + symbol + ": " + CycleStats.describe(e));
                log.warn("Paper trading: no {} bars for {}: {}", base, symbol, e.getMessage());
            }
        });
        return bars;
    }

    // ---- virtual positions ---------------------------------------------------------------------

    /** Applies each strategy's signal to its slot on paper, for every slot that is in play and tradable. */
    private List<Move> decideMoves(Run run, Scope scope, Market market, Map<String, Double> signals,
                                   Map<String, LiveSlotEntity> slots) {
        List<Move> moves = new ArrayList<>();
        for (SlotId id : slotsToVisit(scope, market, slots)) {
            String forcedExit = forcedExit(run.mode(), scope, id);
            Double signal = forcedExit != null ? Double.valueOf(0) : signals.get(id.key());
            if (signal == null) continue;                                    // could not be evaluated this cycle
            Params params = paramsFor(run.cfg(), id.strategy(), market.isShortable(id.symbol()));
            moves.add(Move.decide(id, slots.get(id.key()), signal, market.price(id.symbol()), params, forcedExit, run.now()));
        }
        return moves;
    }

    /** Every strategy on every tradable symbol in scope, and every open slot even if it has left the scope. */
    private static Set<SlotId> slotsToVisit(Scope scope, Market market, Map<String, LiveSlotEntity> slots) {
        Set<SlotId> ids = new LinkedHashSet<>();
        for (TradingStrategy strategy : scope.strategies().values())
            for (String symbol : scope.symbols())
                if (market.isTradable(symbol)) ids.add(new SlotId(strategy.name(), symbol));
        for (LiveSlotEntity s : slots.values())
            if (LiveRows.isOpen(s) && market.isTradable(s.getSymbol())) ids.add(new SlotId(s.getStrategy(), s.getSymbol()));
        return ids;
    }

    /** Why a slot must close whatever its strategy says: the job is flattening, or the slot is no longer in scope; else null. */
    private static String forcedExit(Mode mode, Scope scope, SlotId id) {
        if (mode == Mode.FLATTEN) return SlotLedger.FLATTEN;
        boolean inScope = scope.strategies().containsKey(id.strategy()) && scope.symbols().contains(id.symbol());
        return inScope ? null : SlotLedger.REMOVED;
    }

    private Params paramsFor(LiveSettings cfg, String strategy, boolean shortable) {
        double stopLoss = 0, takeProfit = 0;
        if (cfg.useRiskDefaults() && strategies.isStrategy(strategy)) {
            Double sl = strategies.defaultStopLossPct(strategy), tp = strategies.defaultTakeProfitPct(strategy);
            stopLoss = sl == null ? 0 : sl;
            takeProfit = tp == null ? 0 : tp;
        }
        return new Params(cfg.allocationUsd(), cfg.positionSize(), cfg.allowShort() && shortable, stopLoss, takeProfit);
    }

    /** Per tradable symbol: the shares all strategies hold now, plus what this cycle's moves change. */
    private static Map<String, Double> netVirtualQty(List<Move> moves, Map<String, LiveSlotEntity> slots, Market market) {
        Map<String, Double> net = new HashMap<>();
        for (String symbol : market.tradable().keySet()) net.put(symbol, 0.0);
        for (LiveSlotEntity s : slots.values()) net.computeIfPresent(s.getSymbol(), (symbol, qty) -> qty + LiveRows.signedQty(s));
        for (Move m : moves) net.merge(m.symbol(), m.planned().qtyDelta(), Double::sum);
        return net;
    }

    // ---- orders --------------------------------------------------------------------------------

    /** Records the plans the caps held back and returns their symbols, whose virtual positions must not move. */
    private Set<String> recordHeldBack(Run run, Plan plan) {
        Set<String> heldBack = new HashSet<>();
        for (Skipped skipped : plan.skipped()) {
            heldBack.add(skipped.symbol());
            run.stats().note(skipped.symbol() + " held back (" + skipped.reason().toLowerCase().replace('_', ' ') + ")");
            orders.recordHeldBack(run.cycle(), skipped, run.cfg().dryRun());
        }
        return heldBack;
    }

    /**
     * Sends the plans, those that reduce exposure first, and returns the average fill price of each symbol whose plan filled.
     * A symbol whose orders did not all fill is added to {@code unsettled}.
     */
    private Map<String, Double> sendOrders(Run run, Plan plan, List<Move> moves, Set<String> unsettled) {
        List<SymbolPlan> sequence = new ArrayList<>(plan.withOrders());
        sequence.sort(Comparator.comparing((SymbolPlan p) -> !p.orders().get(0).reducesExposure()).thenComparing(SymbolPlan::symbol));

        Map<String, Double> fills = new HashMap<>();
        for (SymbolPlan sp : sequence) {
            OptionalDouble fill = orders.send(run.cfg(), run.cycle(), sp, reasonOf(sp, moves), run.stats());
            if (fill.isPresent()) fills.put(sp.symbol(), fill.getAsDouble());
            else unsettled.add(sp.symbol());
        }
        return fills;
    }

    private static String reasonOf(SymbolPlan plan, List<Move> moves) {
        long moving = moves.stream().filter(m -> m.symbol().equals(plan.symbol()) && m.planned().qtyDelta() != 0).count();
        return moving == 0 ? "Bringing the account to the strategies' net position"
                : moving + " strateg" + (moving == 1 ? "y" : "ies") + " changed position; net " + String.format("%.2f", plan.virtualQty()) + " shares";
    }

    // ---- saving --------------------------------------------------------------------------------

    /**
     * Makes the moves of every symbol that is not {@code unsettled} real, at the fill price when its order filled, and saves each symbol's slots
     * and closed trades together; open positions elsewhere are only marked to market.
     */
    private void settle(Run run, Map<String, LiveSlotEntity> slots, List<Move> moves, Market market,
                        Map<String, Double> fills, Set<String> unsettled) {
        Map<String, Commit> commits = new LinkedHashMap<>();
        for (Move m : moves) {
            if (unsettled.contains(m.symbol())) continue;
            Transition settled = m.filledAt(fills.getOrDefault(m.symbol(), m.price()), run.now());
            LiveSlotEntity slot = m.slot() != null ? m.slot() : LiveRows.newSlot(m.id().strategy(), m.symbol());
            LiveRows.update(slot, settled.next(), m.signal(), m.price(), run.now());
            slots.put(m.id().key(), slot);

            Commit commit = commits.computeIfAbsent(m.symbol(), k -> new Commit());
            commit.slots().add(slot);
            for (ClosedTrade closed : settled.closed())
                commit.trades().add(LiveRows.trade(m.id().strategy(), m.symbol(), closed, run.cycle().getId()));
            run.stats().tradesClosed += settled.closed().size();
        }
        for (LiveSlotEntity s : slots.values()) {
            Double price = market.prices().get(s.getSymbol());
            if (!LiveRows.isOpen(s) || price == null) continue;
            LiveRows.markToMarket(s, price, run.now());
            commits.computeIfAbsent(s.getSymbol(), k -> new Commit()).slots().add(s);
        }
        commits.forEach((symbol, commit) -> {
            try {
                store.commit(new ArrayList<>(commit.slots()), commit.trades());
            } catch (RuntimeException e) {
                run.stats().error("could not save " + symbol + ": " + e.getMessage());
                log.error("Paper trading: could not commit {}", symbol, e);
            }
        });
    }

    private void saveStrategyPnl(Run run, Map<String, LiveSlotEntity> slots) {
        List<LiveStrategyPnlEntity> rows = LiveRows.strategyPnl(slots.values(), run.cycle().getId(), run.now());
        if (!rows.isEmpty()) store.saveStrategyPnl(rows);
    }

    private void saveEquity(Run run, AccountInfo account) {
        store.saveEquity(LiveRows.equity(account, run.cycle().getId(), run.now()));
    }

    /** The account after this cycle's fills; the earlier reading if the broker cannot be asked. */
    private AccountInfo refreshed(AccountInfo fallback) {
        try { return gateway.account(); } catch (RuntimeException e) { return fallback; }
    }
}
