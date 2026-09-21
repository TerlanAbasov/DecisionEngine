package com.quant.finance.decision.live;

import com.quant.finance.decision.client.AlpacaCredentials;
import com.quant.finance.decision.entity.LiveCycleEntity;
import com.quant.finance.decision.entity.LiveOrderEntity;
import com.quant.finance.decision.entity.LiveSlotEntity;
import com.quant.finance.decision.entity.LiveTradeEntity;
import com.quant.finance.decision.live.AlpacaModels.PositionInfo;
import com.quant.finance.decision.live.LiveDtos.*;
import com.quant.finance.decision.repository.*;
import com.quant.finance.decision.service.StrategyService;
import com.quant.finance.decision.service.UniverseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/** Read side of the paper-trading job: what the UI shows. */
@Service
@Slf4j
@RequiredArgsConstructor
public class LiveQueryService {

    private static final Duration BROKER_TTL = Duration.ofSeconds(8);
    private static final Duration PNL_CURVE_WINDOW = Duration.ofDays(14);
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_TRADES = 500;
    private static final int MAX_EQUITY_HOURS = 24 * 30;

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

    // ---- status ----------------------------------------------------------------------------------

    public StatusDto status() {
        LiveSettings settings = config.current();
        List<LiveSlotEntity> slots = slotRepo.findAll();
        CycleDto lastCycle = cycleRepo.findAllByOrderByIdDesc(PageRequest.of(0, 1)).stream()
                .findFirst().map(LiveQueryService::cycleDto).orElse(null);
        return new StatusDto(settings, settings.enabled(), scheduler.cycleRunning(), scheduler.nextRun(), gateway.isPaper(),
                creds.configured(), broker(), lastCycle, (int) slots.stream().filter(LiveRows::isOpen).count(),
                sum(slots, LiveSlotEntity::getRealizedPnl), sum(slots, LiveRows::unrealized));
    }

    /** Account and market clock, cached briefly so a polling UI does not hammer Alpaca. */
    private BrokerDto broker() {
        Instant now = clock.instant();
        BrokerDto cached = brokerCache;
        if (cached != null && Duration.between(brokerAt, now).compareTo(BROKER_TTL) < 0) return cached;

        BrokerDto fresh = fetchBroker();
        brokerCache = fresh;
        brokerAt = now;
        return fresh;
    }

    private BrokerDto fetchBroker() {
        if (!creds.configured()) return BrokerDto.unavailable("Alpaca credentials are not configured");
        if (!gateway.isPaper()) return BrokerDto.unavailable("The configured trading endpoint is not the paper one — the job will not trade");
        try {
            return BrokerDto.of(gateway.account(), gateway.clock());
        } catch (RuntimeException e) {
            return BrokerDto.unavailable(e.getMessage());
        }
    }

    // ---- strategies ------------------------------------------------------------------------------

    public List<StrategyPerfDto> strategyPerformance() {
        LiveSettings settings = config.current();
        Set<String> inScope = settings.strategiesIn(strategies).keySet();
        Map<String, List<LiveSlotEntity>> slotsByStrategy = slotRepo.findAll().stream()
                .collect(Collectors.groupingBy(LiveSlotEntity::getStrategy));
        Map<String, LiveTradeRepository.StrategyTradeStats> stats = tradeStatsByStrategy();
        double capital = capitalPerStrategy(settings);

        Set<String> names = new TreeSet<>(inScope);
        names.addAll(slotsByStrategy.keySet());
        return names.stream()
                .map(name -> perf(name, inScope.contains(name), slotsByStrategy.getOrDefault(name, List.of()), stats.get(name), capital))
                .sorted(Comparator.comparingDouble(StrategyPerfDto::totalPnl).reversed())
                .toList();
    }

    public StrategyDetailDto strategyDetail(String name, int tradeLimit) {
        LiveSettings settings = config.current();
        List<LiveSlotEntity> slots = slotRepo.findByStrategy(name);
        boolean inScope = settings.strategiesIn(strategies).containsKey(name);
        if (slots.isEmpty() && !inScope && !strategies.isStrategy(name)) throw new NoSuchElementException("Unknown strategy " + name);

        StrategyPerfDto summary = perf(name, inScope, slots, tradeStatsByStrategy().get(name), capitalPerStrategy(settings));
        List<SlotDto> slotDtos = slots.stream().sorted(Comparator.comparing(LiveSlotEntity::getSymbol)).map(LiveQueryService::slotDto).toList();
        List<TradeDto> trades = tradeRepo.findByStrategyOrderByExitTimeDesc(name, PageRequest.of(0, clamp(tradeLimit, MAX_TRADES)))
                .stream().map(LiveQueryService::tradeDto).toList();
        List<PnlPointDto> curve = pnlRepo.findByStrategyAndTsAfterOrderByTs(name, clock.instant().minus(PNL_CURVE_WINDOW)).stream()
                .map(p -> new PnlPointDto(p.getTs(), p.getRealized(), p.getUnrealized(), p.getRealized() + p.getUnrealized())).toList();
        return new StrategyDetailDto(summary, slotDtos, trades, curve);
    }

    private Map<String, LiveTradeRepository.StrategyTradeStats> tradeStatsByStrategy() {
        return tradeRepo.statsPerStrategy().stream()
                .collect(Collectors.toMap(LiveTradeRepository.StrategyTradeStats::getStrategy, Function.identity(), (a, b) -> b));
    }

    /** The money the strategy's slots could put to work at once: every symbol at full signal. */
    private double capitalPerStrategy(LiveSettings settings) {
        int symbols = settings.symbolsIn(universe).size();
        return settings.allocationUsd() * settings.positionSize() * Math.max(1, symbols);
    }

    private static StrategyPerfDto perf(String name, boolean inScope, List<LiveSlotEntity> slots,
                                        LiveTradeRepository.StrategyTradeStats tradeStats, double capital) {
        double realized = sum(slots, LiveSlotEntity::getRealizedPnl);
        double unrealized = sum(slots, LiveRows::unrealized);
        double total = realized + unrealized;
        int open = (int) slots.stream().filter(LiveRows::isOpen).count();
        int longs = (int) slots.stream().filter(s -> s.getDirection() > 0).count();
        Instant lastSignal = slots.stream().map(LiveSlotEntity::getLastSignalAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        int trades = tradeStats == null ? 0 : (int) tradeStats.getTrades();
        int wins = tradeStats == null || tradeStats.getWins() == null ? 0 : tradeStats.getWins().intValue();
        return new StrategyPerfDto(name, inScope, open, longs, open - longs, realized, unrealized, total,
                capital > 0 ? total / capital * 100 : 0, trades, wins, trades > 0 ? 100.0 * wins / trades : null, lastSignal);
    }

    // ---- positions, orders, cycles, equity -------------------------------------------------------

    /** The account's positions next to what the strategies together want, so any gap is visible. */
    public List<PositionDto> positions() {
        Map<String, Double> virtual = new TreeMap<>();
        Map<String, Double> lastPrice = new HashMap<>();
        for (LiveSlotEntity s : slotRepo.findAll()) {
            virtual.merge(s.getSymbol(), LiveRows.signedQty(s), Double::sum);
            if (s.getLastPrice() != null) lastPrice.put(s.getSymbol(), s.getLastPrice());
        }
        Map<String, PositionInfo> actual = gateway.positions().stream()
                .collect(Collectors.toMap(PositionInfo::symbol, Function.identity(), (a, b) -> b));

        Set<String> symbols = new TreeSet<>(virtual.keySet());
        symbols.addAll(actual.keySet());
        List<PositionDto> out = new ArrayList<>();
        for (String symbol : symbols) {
            PositionInfo held = actual.get(symbol);
            double actualQty = held == null ? 0 : held.qty();
            double virtualQty = virtual.getOrDefault(symbol, 0.0);
            out.add(new PositionDto(symbol, actualQty, virtualQty, OrderPlanner.wholeShares(virtualQty) - actualQty,
                    held != null ? held.currentPrice() : lastPrice.get(symbol),
                    held == null ? null : held.marketValue(), held == null ? null : held.unrealizedPl(), virtual.containsKey(symbol)));
        }
        return out;
    }

    public PageDto<OrderDto> orders(int page, int size) {
        Page<LiveOrderEntity> p = orderRepo.findAllByOrderByIdDesc(PageRequest.of(Math.max(0, page), clamp(size, MAX_PAGE_SIZE)));
        return new PageDto<>(p.stream().map(LiveQueryService::orderDto).toList(), p.getTotalElements(), p.getNumber(), p.getSize());
    }

    public PageDto<CycleDto> cycles(int page, int size) {
        Page<LiveCycleEntity> p = cycleRepo.findAllByOrderByIdDesc(PageRequest.of(Math.max(0, page), clamp(size, MAX_PAGE_SIZE)));
        return new PageDto<>(p.stream().map(LiveQueryService::cycleDto).toList(), p.getTotalElements(), p.getNumber(), p.getSize());
    }

    public List<EquityPointDto> equity(int hours) {
        Instant since = clock.instant().minus(Duration.ofHours(clamp(hours, MAX_EQUITY_HOURS)));
        return equityRepo.findByTsAfterOrderByTs(since).stream()
                .map(e -> new EquityPointDto(e.getTs(), e.getEquity(), e.getCash(), e.getBuyingPower())).toList();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static <T> double sum(Collection<T> items, ToDoubleFunction<T> value) {
        return items.stream().mapToDouble(value).sum();
    }

    /** {@code n} kept between 1 and {@code max}. */
    private static int clamp(int n, int max) { return Math.max(1, Math.min(max, n)); }

    private static CycleDto cycleDto(LiveCycleEntity c) {
        return new CycleDto(c.getId(), c.getStartedAt(), c.getFinishedAt(), c.getStatus(), c.getMode(), c.getTriggeredBy(),
                c.isDryRun(), c.getMessage(), c.getSymbols(), c.getStrategies(), c.getSignals(), c.getOrdersPlanned(),
                c.getOrdersFilled(), c.getOrdersFailed(), c.getTradesClosed(), c.getErrors(), c.getDurationMs());
    }

    private static SlotDto slotDto(LiveSlotEntity s) {
        return new SlotDto(s.getSymbol(), s.getDirection(), s.getQty(), s.getEntryPrice(), s.getEntryTime(), s.getLastPrice(),
                LiveRows.unrealized(s), s.getRealizedPnl(), s.getLastSignal(), s.getLastSignalAt(), s.getBlockedDir());
    }

    private static TradeDto tradeDto(LiveTradeEntity t) {
        return new TradeDto(t.getId(), t.getSymbol(), t.getSide(), t.getQty(), t.getEntryTime(), t.getEntryPrice(),
                t.getExitTime(), t.getExitPrice(), t.getPnlUsd(), t.getReturnPct(), t.getExitReason());
    }

    private static OrderDto orderDto(LiveOrderEntity o) {
        return new OrderDto(o.getId(), o.getCycleId(), o.getSymbol(), o.getSide(), o.getQty(), o.getStatus(), o.getAlpacaOrderId(),
                o.getFilledQty(), o.getFilledAvgPrice(), o.getSubmittedAt(), o.getFilledAt(), o.getError(), o.isDryRun(),
                o.getReason(), o.getTargetQty(), o.getPositionBefore());
    }
}
