package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.autotrade.AutoTradeSettings.OrderType;
import com.quant.finance.decision.autotrade.AutoTradeSettings.TimeInForce;
import com.quant.finance.decision.autotrade.CommandForwarder.Outcome;
import com.quant.finance.decision.autotrade.CommandSender.SendResult;
import com.quant.finance.decision.autotrade.TradeCommand.Side;
import com.quant.finance.decision.engine.Timeframe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CommandForwarderTest {

    private static final Instant NOW = Instant.parse("2026-09-21T15:00:30Z");
    private static final Instant BAR = Instant.parse("2026-09-21T14:45:00Z");

    private AutoTradeConfigService config;
    private AutoTradeCommandRepository repo;
    private CommandSender sender;
    private CommandForwarder forwarder;
    private final List<AutoTradeCommandEntity> saved = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();
    private final List<TradeCommand> sent = new ArrayList<>();
    private final List<String> events = new ArrayList<>();

    private static AutoTradeSettings settings(boolean enabled, int max) {
        return new AutoTradeSettings(enabled, Instant.parse("2026-09-21T14:00:00Z"), 5, OrderType.MKT, TimeInForce.DAY, List.of(), List.of(), max);
    }

    private static Flip flip(String symbol, String strategy) {
        return new Flip(strategy, symbol, Timeframe.M15, Side.BUY, BAR, 100);
    }

    @BeforeEach
    void setUp() {
        config = mock(AutoTradeConfigService.class);
        when(config.current()).thenReturn(settings(true, 25));
        repo = mock(AutoTradeCommandRepository.class);
        when(repo.saveAndFlush(any())).thenAnswer(i -> {
            AutoTradeCommandEntity row = i.getArgument(0);
            if (!keys.add(row.getStrategy() + "|" + row.getSymbol() + "|" + row.getTimeframe() + "|" + row.getBarOpen()))
                throw new DataIntegrityViolationException("uk_auto_trade_command_bar");
            row.setId((long) (saved.size() + 1));
            saved.add(row);
            events.add("recorded " + row.getStatus());
            return row;
        });
        sender = mock(CommandSender.class);
        when(sender.send(any())).thenAnswer(i -> {
            sent.add(i.getArgument(0));
            events.add("sent");
            return new SendResult(CommandStatus.SENT, "📊 Symbol will be bought");
        });
        forwarder = new CommandForwarder(config, new CommandLog(repo, Clock.fixed(NOW, ZoneOffset.UTC)), sender);
    }

    @Test
    void everyFlipBecomesACommandThatIsRecordedWithTheReplyAndSentInOrder() {
        Outcome outcome = forwarder.forward(List.of(flip("MSFT", "rsi"), flip("AAPL", "rsi"), flip("AAPL", "macd")));

        assertEquals(new Outcome(3, 0, 0, 0), outcome);
        assertEquals(List.of("AAPL", "AAPL", "MSFT"), sent.stream().map(TradeCommand::identifier).toList());
        assertEquals(List.of("macd", "rsi"), sent.subList(0, 2).stream().map(TradeCommand::strategy).toList(), "sorted by symbol, then strategy");
        assertTrue(saved.stream().allMatch(r -> r.getStatus().equals("SENT") && "📊 Symbol will be bought".equals(r.getResponse())));
        assertEquals(5, sent.get(0).quantity());
    }

    @Test
    void theCommandIsWrittenDownBeforeItIsSentSoACrashCanNeverSendItTwice() {
        forwarder.forward(List.of(flip("AAPL", "rsi")));
        assertEquals(List.of("recorded PENDING", "sent"), events);
        assertEquals("SENT", saved.get(0).getStatus(), "and updated once the reply is in");
    }

    @Test
    void aBarThatAlreadyHasACommandIsNotSentAgain() {
        forwarder.forward(List.of(flip("AAPL", "rsi")));
        Outcome again = forwarder.forward(List.of(flip("AAPL", "rsi"), flip("AAPL", "macd")));

        assertEquals(new Outcome(1, 0, 0, 0), again, "only the strategy that had no command yet");
        assertEquals(2, sent.size());
        assertEquals(2, saved.size());
    }

    @Test
    void theSameStrategyOnALaterBarIsANewCommand() {
        forwarder.forward(List.of(flip("AAPL", "rsi")));
        forwarder.forward(List.of(new Flip("rsi", "AAPL", Timeframe.M15, Side.SELL, BAR.plusSeconds(900), 99)));
        assertEquals(2, sent.size());
    }

    @Test
    void switchingTheJobOffStopsTheRunBeforeTheNextCommand() {
        when(config.current()).thenReturn(settings(true, 25), settings(false, 25));
        Outcome outcome = forwarder.forward(List.of(flip("AAPL", "rsi"), flip("MSFT", "rsi"), flip("TSLA", "rsi")));

        assertEquals(new Outcome(1, 0, 0, 0), outcome);
        assertEquals(List.of("AAPL"), sent.stream().map(TradeCommand::identifier).toList());
        assertEquals(1, saved.size(), "nothing is recorded for commands that were never sent");
    }

    @Test
    void aDisabledJobSendsNothingAtAll() {
        when(config.current()).thenReturn(settings(false, 25));
        assertEquals(new Outcome(0, 0, 0, 0), forwarder.forward(List.of(flip("AAPL", "rsi"))));
        verifyNoInteractions(sender);
        assertTrue(saved.isEmpty());
    }

    @Test
    void theLimitPerRunHoldsBackTheRestAndRecordsThemAsSkippedSoTheyAreNotSentLater() {
        when(config.current()).thenReturn(settings(true, 2));
        Outcome outcome = forwarder.forward(List.of(flip("A", "s"), flip("B", "s"), flip("C", "s"), flip("D", "s")));

        assertEquals(new Outcome(2, 0, 0, 2), outcome);
        assertEquals(2, sent.size());
        List<AutoTradeCommandEntity> skipped = saved.stream().filter(r -> r.getStatus().equals("SKIPPED")).toList();
        assertEquals(List.of("C", "D"), skipped.stream().map(AutoTradeCommandEntity::getSymbol).toList());
        assertTrue(skipped.get(0).getResponse().contains("limit of 2"));

        forwarder.forward(List.of(flip("C", "s")));
        assertEquals(2, sent.size(), "a skipped bar never comes back");
    }

    @Test
    void whatExecutionEngineAnsweredIsWhatGetsRecorded() {
        when(sender.send(any())).thenReturn(new SendResult(CommandStatus.REJECTED, "❌ Error while executing command: x"),
                new SendResult(CommandStatus.FAILED, "No answer from ExecutionEngine"), new SendResult(CommandStatus.SENT, "ok"));

        Outcome outcome = forwarder.forward(List.of(flip("A", "s"), flip("B", "s"), flip("C", "s")));

        assertEquals(new Outcome(1, 1, 1, 0), outcome);
        assertEquals(List.of("REJECTED", "FAILED", "SENT"), saved.stream().map(AutoTradeCommandEntity::getStatus).toList());
        assertEquals("No answer from ExecutionEngine", saved.get(1).getResponse());
    }

    @Test
    void aFailedCommandIsNotRetriedOnTheNextRun() {
        when(sender.send(any())).thenReturn(new SendResult(CommandStatus.FAILED, "timeout"));
        forwarder.forward(List.of(flip("A", "s")));
        forwarder.forward(List.of(flip("A", "s")));
        verify(sender, times(1)).send(any());
    }

    @Test
    void ifTheCommandCannotBeRecordedNothingIsSent() {
        doThrow(new IllegalStateException("db down")).when(repo).saveAndFlush(any());
        assertThrows(IllegalStateException.class, () -> forwarder.forward(List.of(flip("A", "s"))));
        verifyNoInteractions(sender);
    }

    @Test
    void overlongRepliesAreTruncatedToWhatTheColumnHolds() {
        when(sender.send(any())).thenReturn(new SendResult(CommandStatus.REJECTED, "x".repeat(2000)));
        forwarder.forward(List.of(flip("A", "s")));
        assertEquals(AutoTradeCommandEntity.RESPONSE_LENGTH, saved.get(0).getResponse().length());
        assertTrue(saved.get(0).getResponse().endsWith("…"));
    }
}
