package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.autotrade.AutoTradeSettings.OrderType;
import com.quant.finance.decision.autotrade.AutoTradeSettings.TimeInForce;
import com.quant.finance.decision.autotrade.CommandSender.SendResult;
import com.quant.finance.decision.autotrade.TradeCommand.Side;
import com.quant.finance.decision.engine.Timeframe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CommandLogTest {

    private static final Instant NOW = Instant.parse("2026-09-23T19:41:30Z");
    private static final Instant BAR = Instant.parse("2026-09-23T19:15:00Z");
    private static final AutoTradeSettings SETTINGS = new AutoTradeSettings(true, Instant.parse("2026-09-23T18:00:00Z"), 5,
            OrderType.MKT, TimeInForce.DAY, List.of(), List.of(), 25);
    private static final Flip FLIP = new Flip("rsi2", "AAOI", Timeframe.M15, Side.BUY, BAR, 100);

    private AutoTradeCommandRepository repo;
    private CommandLog log;

    @BeforeEach
    void setUp() {
        repo = mock(AutoTradeCommandRepository.class);
        log = new CommandLog(repo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aBarThatAlreadyHasACommandIsNeverEvenAttemptedAsAWriteSoNothingLogsAConstraintViolation() {
        when(repo.existsByStrategyAndSymbolAndTimeframeAndBarOpen("rsi2", "AAOI", "M15", BAR)).thenReturn(true);

        Optional<AutoTradeCommandEntity> result = log.record(FLIP, SETTINGS, CommandStatus.PENDING, null);

        assertTrue(result.isEmpty());
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void aNewBarIsWrittenWithEveryFieldFromTheFlipAndTheSettings() {
        when(repo.existsByStrategyAndSymbolAndTimeframeAndBarOpen(any(), any(), any(), any())).thenReturn(false);
        when(repo.saveAndFlush(any())).thenAnswer(i -> { i.<AutoTradeCommandEntity>getArgument(0).setId(9L); return i.getArgument(0); });

        AutoTradeCommandEntity row = log.record(FLIP, SETTINGS, CommandStatus.PENDING, null).orElseThrow();

        assertEquals(9L, row.getId());
        assertEquals(NOW, row.getCreatedAt());
        assertEquals("rsi2", row.getStrategy());
        assertEquals("AAOI", row.getSymbol());
        assertEquals("M15", row.getTimeframe());
        assertEquals(BAR, row.getBarOpen());
        assertEquals("BUY", row.getSide());
        assertEquals(5, row.getQuantity());
        assertEquals("MKT", row.getOrderType());
        assertNull(row.getLimitPrice(), "a market order carries no limit price");
        assertEquals("PENDING", row.getStatus());
    }

    @Test
    void aLimitOrderRecordsTheFlipsCloseAsItsLimitPrice() {
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        AutoTradeSettings limit = new AutoTradeSettings(true, SETTINGS.enabledSince(), 5, OrderType.LMT, TimeInForce.GTC, List.of(), List.of(), 25);

        AutoTradeCommandEntity row = log.record(FLIP, limit, CommandStatus.PENDING, null).orElseThrow();

        assertEquals(100.0, row.getLimitPrice());
    }

    @Test
    void aRaceThatSlipsPastTheExistsCheckIsStillCaughtByTheUniqueKeyInstead() {
        when(repo.existsByStrategyAndSymbolAndTimeframeAndBarOpen(any(), any(), any(), any())).thenReturn(false);
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_auto_trade_command_bar"));

        assertDoesNotThrow(() -> assertTrue(log.record(FLIP, SETTINGS, CommandStatus.PENDING, null).isEmpty()));
    }

    @Test
    void finishSavesTheOutcomeOntoTheRow() {
        AutoTradeCommandEntity row = new AutoTradeCommandEntity();
        row.setStatus("PENDING");
        log.finish(row, new SendResult(CommandStatus.SENT, "📊 Symbol will be bought"));

        assertEquals("SENT", row.getStatus());
        assertEquals("📊 Symbol will be bought", row.getResponse());
        verify(repo).save(row);
    }

    @Test
    void anOverlongResponseIsTruncatedToWhatTheColumnHolds() {
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        AutoTradeCommandEntity row = log.record(FLIP, SETTINGS, CommandStatus.PENDING, "x".repeat(2000)).orElseThrow();

        assertEquals(AutoTradeCommandEntity.RESPONSE_LENGTH, row.getResponse().length());
        assertTrue(row.getResponse().endsWith("…"));
    }
}
