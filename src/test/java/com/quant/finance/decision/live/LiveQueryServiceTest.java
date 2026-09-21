package com.quant.finance.decision.live;

import com.quant.finance.decision.client.AlpacaCredentials;
import com.quant.finance.decision.entity.LiveSlotEntity;
import com.quant.finance.decision.live.AlpacaModels.PositionInfo;
import com.quant.finance.decision.live.LiveDtos.PositionDto;
import com.quant.finance.decision.live.LiveDtos.StatusDto;
import com.quant.finance.decision.live.LiveDtos.StrategyPerfDto;
import com.quant.finance.decision.repository.*;
import com.quant.finance.decision.service.StrategyService;
import com.quant.finance.decision.service.UniverseService;
import com.quant.finance.decision.strategy.TradingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T15:00:00Z");

    private LiveConfigService config;
    private TradingGateway gateway;
    private StrategyService strategies;
    private LiveSlotRepository slotRepo;
    private LiveTradeRepository tradeRepo;
    private LiveCycleRepository cycleRepo;
    private LiveQueryService service;

    @BeforeEach
    void setUp() {
        config = mock(LiveConfigService.class);
        when(config.current()).thenReturn(LiveSettings.defaults());
        gateway = mock(TradingGateway.class);
        strategies = mock(StrategyService.class);
        when(strategies.getEnabledStrategies()).thenReturn(Map.of("s1", mock(TradingStrategy.class)));
        UniverseService universe = mock(UniverseService.class);
        when(universe.get()).thenReturn(List.of("A", "B"));
        slotRepo = mock(LiveSlotRepository.class);
        tradeRepo = mock(LiveTradeRepository.class);
        cycleRepo = mock(LiveCycleRepository.class);
        when(cycleRepo.findAllByOrderByIdDesc(any())).thenReturn(new PageImpl<>(List.of()));
        AlpacaCredentials creds = mock(AlpacaCredentials.class);
        when(creds.configured()).thenReturn(false);
        service = new LiveQueryService(config, mock(LiveTradingScheduler.class), gateway, creds, strategies, universe,
                slotRepo, tradeRepo, mock(LiveOrderRepository.class), cycleRepo, mock(LiveEquityRepository.class),
                mock(LiveStrategyPnlRepository.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static LiveSlotEntity slot(String strategy, String symbol, int direction, double qty, Double entry, Double last, double realized) {
        LiveSlotEntity s = LiveRows.newSlot(strategy, symbol);
        s.setDirection(direction);
        s.setQty(qty);
        s.setEntryPrice(entry);
        s.setLastPrice(last);
        s.setRealizedPnl(realized);
        return s;
    }

    @Test
    void statusTotalsRealizedAndOpenProfitAcrossSlotsAndReportsMissingCredentials() {
        when(slotRepo.findAll()).thenReturn(List.of(
                slot("s1", "A", 1, 10, 100.0, 105.0, 5),          // +50 open
                slot("s1", "B", -1, 10, 100.0, 105.0, 0),         // -50 open
                slot("s2", "A", 0, 0, null, null, 12)));          // flat, realized only
        StatusDto st = service.status();
        assertEquals(2, st.openSlots());
        assertEquals(17, st.realizedPnl(), 1e-9);
        assertEquals(0, st.unrealizedPnl(), 1e-9);
        assertFalse(st.broker().reachable());
        assertEquals("Alpaca credentials are not configured", st.broker().error());
    }

    @Test
    void strategyPerformanceListsInScopeAndTradedStrategiesBestFirst() {
        when(slotRepo.findAll()).thenReturn(List.of(
                slot("s1", "A", 1, 10, 100.0, 110.0, 20),         // total 120
                slot("old", "A", -1, 5, 100.0, 90.0, 0)));        // total 50, no longer in scope
        LiveTradeRepository.StrategyTradeStats stats = mock(LiveTradeRepository.StrategyTradeStats.class);
        when(stats.getStrategy()).thenReturn("s1");
        when(stats.getTrades()).thenReturn(4L);
        when(stats.getWins()).thenReturn(3L);
        when(tradeRepo.statsPerStrategy()).thenReturn(List.of(stats));

        List<StrategyPerfDto> out = service.strategyPerformance();
        assertEquals(List.of("s1", "old"), out.stream().map(StrategyPerfDto::strategy).toList());
        StrategyPerfDto s1 = out.get(0);
        assertTrue(s1.inScope());
        assertEquals(1, s1.longs());
        assertEquals(0, s1.shorts());
        assertEquals(120, s1.totalPnl(), 1e-9);
        assertEquals(75.0, s1.winRatePct(), 1e-9);
        assertEquals(120 / (100 * 1.0 * 2) * 100, s1.returnPct(), 1e-9);   // $100 x 2 symbols of capital
        StrategyPerfDto old = out.get(1);
        assertFalse(old.inScope());
        assertEquals(1, old.shorts());
        assertNull(old.winRatePct());
    }

    @Test
    void positionsShowTheGapBetweenTheAccountAndWhatTheStrategiesWant() {
        when(slotRepo.findAll()).thenReturn(List.of(slot("s1", "A", 1, 10, 100.0, 105.0, 0), slot("s2", "A", 1, 10, 100.0, 105.0, 0)));
        when(gateway.positions()).thenReturn(List.of(
                new PositionInfo("A", 15, 100, 105, 1575, 75), new PositionInfo("Z", 3, 10, 11, 33, 3)));

        List<PositionDto> out = service.positions();
        assertEquals(List.of("A", "Z"), out.stream().map(PositionDto::symbol).toList());
        assertEquals(5, out.get(0).gap(), 1e-9);                       // wants 20, holds 15
        assertTrue(out.get(0).managed());
        assertEquals(-3, out.get(1).gap(), 1e-9);                      // not wanted by any strategy
        assertFalse(out.get(1).managed());
    }

    @Test
    void anUnknownStrategyHasNoDetail() {
        when(slotRepo.findByStrategy("nope")).thenReturn(List.of());
        when(strategies.isStrategy("nope")).thenReturn(false);
        assertThrows(NoSuchElementException.class, () -> service.strategyDetail("nope", 10));
    }
}
