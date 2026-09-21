package com.quant.finance.decision.live;

import com.quant.finance.decision.client.AlpacaCredentials;
import com.quant.finance.decision.domain.*;
import com.quant.finance.decision.live.AlpacaModels.*;
import com.quant.finance.decision.live.LiveDtos.*;
import com.quant.finance.decision.repository.*;
import com.quant.finance.decision.service.StrategyService;
import com.quant.finance.decision.service.UniverseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Read side of the paper-trading job: what the UI shows. */
@Service
@Slf4j
public class LiveQueryService {

    private static final Duration BROKER_TTL = Duration.ofSeconds(8);

    private final LiveConfigService config;
    private final LiveTradingScheduler scheduler;
    private final TradingGateway gateway;
    private final AlpacaCredentials creds;
    private final StrategyService strategies;
    private final UniverseService universe;
    private final LiveSlotRepository slotRepo;
    private final LiveTradeRepository tradeRepo;
    private final LiveOrderRepository orderRepo;
    private final LiveCycleRepository cycleRepo;
    private final LiveEquityRepository equityRepo;
    private final LiveStrategyPnlRepository pnlRepo;
    private final Clock clock;

    private volatile BrokerDto brokerCache;
    private volatile Instant brokerAt = Instant.EPOCH;

    public LiveQueryService(LiveConfigService config, LiveTradingScheduler scheduler, TradingGateway gateway,
                            AlpacaCredentials creds, StrategyService strategies, UniverseService universe,
                            LiveSlotRepository slotRepo, LiveTradeRepository tradeRepo, LiveOrderRepository orderRepo,
                            LiveCycleRepository cycleRepo, LiveEquityRepository equityRepo,
                            LiveStrategyPnlRepository pnlRepo, Clock clock) {
        this.config = config;
        this.scheduler = scheduler;
        this.gateway = gateway;
        this.creds = creds;
        this.strategies = strategies;
        this.universe = universe;
        this.slotRepo = slotRepo;
        this.tradeRepo = tradeRepo;
        this.orderRepo = orderRepo;
        this.cycleRepo = cycleRepo;
        this.equityRepo = equityRepo;
        this.pnlRepo = pnlRepo;
        this.clock = clock;
    }

    // ---- status ----------------------------------------------------------------------------------

    public StatusDto status() {
        LiveSettings s = config.current();
        List<LiveSlotEntity> slots = slotRepo.findAll();
        double realized = 0, unrealized = 0;
        int open = 0;
        for (LiveSlotEntity sl : slots) {
            realized += sl.getRealizedPnl();
            if (sl.getDirection() != 0) { open++; unrealized += unrealized(sl); }
        }
        CycleDto last = cycleRepo.findAllByOrderByIdDesc(PageRequest.of(0, 1)).stream().findFirst().map(LiveQueryService::dto).orElse(null);
        return new StatusDto(s, s.enabled(), scheduler.cycleRunning(), scheduler.nextRun(), gateway.isPaper(),
                creds.configured(), broker(), last, open, realized, unrealized);
    }

    /** Account and market clock, cached briefly so a polling UI does not hammer Alpaca. */
    private BrokerDto broker() {
        Instant now = clock.instant();
        BrokerDto cached = brokerCache;
        if (cached != null && Duration.between(brokerAt, now).compareTo(BROKER_TTL) < 0) return cached;
        BrokerDto fresh;
        if (!creds.configured()) {
            fresh = new BrokerDto(false, "Alpaca credentials are not configured", null, null, null, null, null, null, null, null, null);
        } else if (!gateway.isPaper()) {
            fresh = new BrokerDto(false, "The configured trading endpoint is not the paper one — the job will not trade", null, null, null, null, null, null, null, null, null);
        } else {
            try {
                AccountInfo a = gateway.account();
                MarketClock c = gateway.clock();
                fresh = new BrokerDto(true, null, a.status(), a.equity(), a.cash(), a.buyingPower(), a.longMarketValue(),
                        a.shortMarketValue(), c.open(), c.nextOpen(), c.nextClose());
            } catch (RuntimeException e) {
                fresh = new BrokerDto(false, e.getMessage(), null, null, null, null, null, null, null, null, null);
            }
        }
        brokerCache = fresh;
        brokerAt = now;
        return fresh;
    }

    // ---- strategies ------------------------------------------------------------------------------

    public List<StrategyPerfDto> strategyPerformance() {
        LiveSettings s = config.current();
        Set<String> inScope = new LinkedHashSet<>(s.strategyNames().isEmpty()
                ? strategies.getEnabledStrategies().keySet() : strategies.getStrategies(s.strategyNames()).keySet());
        Map<String, List<LiveSlotEntity>> byStrategy = new TreeMap<>();
        for (LiveSlotEntity sl : slotRepo.findAll()) byStrategy.computeIfAbsent(sl.getStrategy(), k -> new ArrayList<>()).add(sl);
        Map<String, LiveTradeRepository.StrategyTradeStats> stats = new HashMap<>();
        for (LiveTradeRepository.StrategyTradeStats st : tradeRepo.statsPerStrategy()) stats.put(st.getStrategy(), st);
        double capital = capitalPerStrategy(s);

        Set<String> names = new TreeSet<>(inScope);
        names.addAll(byStrategy.keySet());
        List<StrategyPerfDto> out = new ArrayList<>();
        for (String name : names) out.add(perf(name, inScope.contains(name), byStrategy.getOrDefault(name, List.of()), stats.get(name), capital));
        out.sort(Comparator.comparingDouble(StrategyPerfDto::totalPnl).reversed());
        return out;
    }

    private double capitalPerStrategy(LiveSettings s) {
        int symbols = s.symbols().isEmpty() ? universe.get().size() : s.symbols().size();
        return s.allocationUsd() * s.positionSize() * Math.max(1, symbols);
    }

    private static StrategyPerfDto perf(String name, boolean inScope, List<LiveSlotEntity> slots,
                                        LiveTradeRepository.StrategyTradeStats st, double capital) {
        double realized = 0, unrealized = 0;
        int open = 0, longs = 0, shorts = 0;
        Instant lastSignal = null;
        for (LiveSlotEntity sl : slots) {
            realized += sl.getRealizedPnl();
            if (sl.getDirection() != 0) {
                open++;
                if (sl.getDirection() > 0) longs++; else shorts++;
                unrealized += unrealized(sl);
            }
            if (sl.getLastSignalAt() != null && (lastSignal == null || sl.getLastSignalAt().isAfter(lastSignal))) lastSignal = sl.getLastSignalAt();
        }
        int trades = st == null ? 0 : (int) st.getTrades();
        int wins = st == null || st.getWins() == null ? 0 : st.getWins().intValue();
        double total = realized + unrealized;
        return new StrategyPerfDto(name, inScope, open, longs, shorts, realized, unrealized, total,
                capital > 0 ? total / capital * 100 : 0, trades, wins, trades > 0 ? 100.0 * wins / trades : null, lastSignal);
    }

    public StrategyDetailDto strategyDetail(String name, int tradeLimit) {
        List<LiveSlotEntity> slots = slotRepo.findByStrategy(name);
        LiveSettings s = config.current();
        boolean inScope = (s.strategyNames().isEmpty() ? strategies.getEnabledStrategies().keySet()
                : strategies.getStrategies(s.strategyNames()).keySet()).contains(name);
        if (slots.isEmpty() && !inScope && !strategies.isStrategy(name)) throw new NoSuchElementException("Unknown strategy " + name);
        StrategyPerfDto summary = perf(name, inScope, slots, statsFor(name), capitalPerStrategy(s));
        List<SlotDto> slotDtos = slots.stream().sorted(Comparator.comparing(LiveSlotEntity::getSymbol))
                .map(sl -> new SlotDto(sl.getSymbol(), sl.getDirection(), sl.getQty(), sl.getEntryPrice(), sl.getEntryTime(),
                        sl.getLastPrice(), unrealized(sl), sl.getRealizedPnl(), sl.getLastSignal(), sl.getLastSignalAt(), sl.getBlockedDir()))
                .toList();
        List<TradeDto> trades = tradeRepo.findByStrategyOrderByExitTimeDesc(name, PageRequest.of(0, Math.max(1, Math.min(500, tradeLimit))))
                .stream().map(t -> new TradeDto(t.getId(), t.getSymbol(), t.getSide(), t.getQty(), t.getEntryTime(), t.getEntryPrice(),
                        t.getExitTime(), t.getExitPrice(), t.getPnlUsd(), t.getReturnPct(), t.getExitReason())).toList();
        List<PnlPointDto> curve = pnlRepo.findByStrategyAndTsAfterOrderByTs(name, clock.instant().minus(Duration.ofDays(14))).stream()
                .map(p -> new PnlPointDto(p.getTs(), p.getRealized(), p.getUnrealized(), p.getRealized() + p.getUnrealized())).toList();
        return new StrategyDetailDto(summary, slotDtos, trades, curve);
    }

    private LiveTradeRepository.StrategyTradeStats statsFor(String name) {
        return tradeRepo.statsPerStrategy().stream().filter(x -> x.getStrategy().equals(name)).findFirst().orElse(null);
    }

    // ---- positions, orders, cycles, equity -------------------------------------------------------

    /** The account's positions next to what the strategies together want, so any gap is visible. */
    public List<PositionDto> positions() {
        Map<String, Double> virtual = new TreeMap<>();
        Map<String, Double> lastPrice = new HashMap<>();
        for (LiveSlotEntity sl : slotRepo.findAll()) {
            virtual.merge(sl.getSymbol(), sl.getDirection() * sl.getQty(), Double::sum);
            if (sl.getLastPrice() != null) lastPrice.put(sl.getSymbol(), sl.getLastPrice());
        }
        Map<String, PositionInfo> actual = new TreeMap<>();
        for (PositionInfo p : gateway.positions()) actual.put(p.symbol(), p);
        Set<String> symbols = new TreeSet<>(virtual.keySet());
        symbols.addAll(actual.keySet());
        List<PositionDto> out = new ArrayList<>();
        for (String sym : symbols) {
            PositionInfo p = actual.get(sym);
            double a = p == null ? 0 : p.qty(), v = virtual.getOrDefault(sym, 0.0);
            out.add(new PositionDto(sym, a, v, OrderPlanner.wholeShares(v) - a, p != null ? p.currentPrice() : lastPrice.get(sym),
                    p == null ? null : p.marketValue(), p == null ? null : p.unrealizedPl(), virtual.containsKey(sym)));
        }
        return out;
    }

    public PageDto<OrderDto> orders(int page, int size) {
        Page<LiveOrderEntity> p = orderRepo.findAllByOrderByIdDesc(PageRequest.of(Math.max(0, page), clampSize(size)));
        return new PageDto<>(p.stream().map(o -> new OrderDto(o.getId(), o.getCycleId(), o.getSymbol(), o.getSide(), o.getQty(),
                o.getStatus(), o.getAlpacaOrderId(), o.getFilledQty(), o.getFilledAvgPrice(), o.getSubmittedAt(), o.getFilledAt(),
                o.getError(), o.isDryRun(), o.getReason(), o.getTargetQty(), o.getPositionBefore())).toList(),
                p.getTotalElements(), p.getNumber(), p.getSize());
    }

    public PageDto<CycleDto> cycles(int page, int size) {
        Page<LiveCycleEntity> p = cycleRepo.findAllByOrderByIdDesc(PageRequest.of(Math.max(0, page), clampSize(size)));
        return new PageDto<>(p.stream().map(LiveQueryService::dto).toList(), p.getTotalElements(), p.getNumber(), p.getSize());
    }

    public List<EquityPointDto> equity(int hours) {
        Instant since = clock.instant().minus(Duration.ofHours(Math.max(1, Math.min(24 * 30, hours))));
        return equityRepo.findByTsAfterOrderByTs(since).stream()
                .map(e -> new EquityPointDto(e.getTs(), e.getEquity(), e.getCash(), e.getBuyingPower())).toList();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static double unrealized(LiveSlotEntity s) {
        return s.getDirection() == 0 || s.getLastPrice() == null || s.getEntryPrice() == null ? 0
                : s.getDirection() * s.getQty() * (s.getLastPrice() - s.getEntryPrice());
    }

    private static int clampSize(int size) { return Math.max(1, Math.min(200, size)); }

    private static CycleDto dto(LiveCycleEntity c) {
        return new CycleDto(c.getId(), c.getStartedAt(), c.getFinishedAt(), c.getStatus(), c.getMode(), c.getTriggeredBy(),
                c.isDryRun(), c.getMessage(), c.getSymbols(), c.getStrategies(), c.getSignals(), c.getOrdersPlanned(),
                c.getOrdersFilled(), c.getOrdersFailed(), c.getTradesClosed(), c.getErrors(), c.getDurationMs());
    }
}
