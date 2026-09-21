package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.autotrade.AutoTradeSettings.OrderType;
import com.quant.finance.decision.autotrade.AutoTradeSettings.TimeInForce;
import com.quant.finance.decision.service.StrategyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AutoTradeConfigServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T15:00:00Z");

    private AutoTradeConfigRepository repo;
    private CommandSender sender;
    private AutoTradeConfigService service;
    private AutoTradeConfigEntity stored;

    @BeforeEach
    void setUp() {
        repo = mock(AutoTradeConfigRepository.class);
        stored = null;
        when(repo.findById(1L)).thenAnswer(i -> Optional.ofNullable(stored));
        when(repo.save(any())).thenAnswer(i -> { stored = i.getArgument(0); return stored; });
        StrategyService strategies = mock(StrategyService.class);
        when(strategies.isStrategy(any())).thenAnswer(i -> List.of("rsi", "macd").contains(i.<String>getArgument(0)));
        sender = mock(CommandSender.class);
        when(sender.isConfigured()).thenReturn(true);
        service = new AutoTradeConfigService(repo, strategies, sender, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AutoTradeSettings form(double quantity, List<String> names) {
        return new AutoTradeSettings(false, null, quantity, OrderType.LMT, TimeInForce.GTC, names, List.of("aapl"), 10);
    }

    @Test
    void theFirstReadCreatesTheOffByDefaultRow() {
        AutoTradeSettings s = service.current();
        assertFalse(s.enabled());
        assertNotNull(stored, "the defaults are saved");
        assertFalse(stored.isEnabled());
        assertSame(s, service.current(), "and cached afterwards");
        verify(repo, times(1)).findById(1L);
    }

    @Test
    void enablingRecordsWhenAndPersistsIt() {
        AutoTradeSettings on = service.enable();
        assertTrue(on.enabled());
        assertEquals(NOW, on.enabledSince());
        assertTrue(stored.isEnabled());
        assertEquals(NOW, stored.getEnabledSince());
        assertTrue(service.current().enabled());
    }

    @Test
    void enablingTwiceKeepsTheOriginalTime() {
        service.enable();
        AutoTradeConfigService later = new AutoTradeConfigService(repo, mock(StrategyService.class), sender,
                Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC));
        assertEquals(NOW, later.enable().enabledSince(), "read back from the row, not restamped");
    }

    @Test
    void enablingIsRefusedWhileExecutionEngineIsNotConfigured() {
        when(sender.isConfigured()).thenReturn(false);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.enable());
        assertTrue(e.getMessage().contains("decision.execution-engine.url"));
        assertFalse(service.current().enabled());
    }

    @Test
    void disablingSwitchesOffAndForgetsTheTime() {
        service.enable();
        AutoTradeSettings off = service.disable();
        assertFalse(off.enabled());
        assertNull(off.enabledSince());
        assertFalse(stored.isEnabled());
        assertNull(stored.getEnabledSince());
        assertFalse(service.disable().enabled(), "disabling twice is harmless");
    }

    @Test
    void savingTheFormKeepsTheSwitchAsItIs() {
        service.enable();
        AutoTradeSettings saved = service.update(form(7, List.of("rsi")));

        assertTrue(saved.enabled());
        assertEquals(NOW, saved.enabledSince());
        assertEquals(7, saved.quantity());
        assertEquals(OrderType.LMT, saved.orderType());
        assertEquals(List.of("AAPL"), saved.symbols(), "normalised");
        assertEquals(7, stored.getQuantity());
        assertEquals("rsi", stored.getStrategyNames());
        assertEquals("AAPL", stored.getSymbols());
        assertEquals("LMT", stored.getOrderType());
        assertEquals("GTC", stored.getTif());
    }

    @Test
    void aFormThatTriesToSwitchTheJobOnDoesNotDoSo() {
        AutoTradeSettings sneaky = new AutoTradeSettings(true, NOW, 3, OrderType.MKT, TimeInForce.DAY, List.of(), List.of(), 5);
        assertFalse(service.update(sneaky).enabled());
    }

    @Test
    void anInvalidFormChangesNothing() {
        service.current();
        int saves = mockingDetails(repo).getInvocations().size();
        assertThrows(IllegalArgumentException.class, () -> service.update(form(0, List.of())));
        assertThrows(IllegalArgumentException.class, () -> service.update(form(1, List.of("nope"))));
        assertEquals(saves, mockingDetails(repo).getInvocations().size(), "nothing was written");
        assertEquals(1, service.current().quantity());
    }

    @Test
    void savedSettingsAreReadBackFromTheRowOnStartup() {
        service.enable();
        service.update(form(4, List.of("macd")));
        AutoTradeConfigService restarted = new AutoTradeConfigService(repo, mock(StrategyService.class), sender, Clock.fixed(NOW, ZoneOffset.UTC));

        AutoTradeSettings s = restarted.current();
        assertTrue(s.enabled());
        assertEquals(NOW, s.enabledSince());
        assertEquals(4, s.quantity());
        assertEquals(List.of("macd"), s.strategyNames());
        assertEquals(TimeInForce.GTC, s.tif());
    }
}
