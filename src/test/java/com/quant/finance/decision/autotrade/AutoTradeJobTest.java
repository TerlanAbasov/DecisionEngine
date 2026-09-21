package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.autotrade.AutoTradeSettings.OrderType;
import com.quant.finance.decision.autotrade.AutoTradeSettings.TimeInForce;
import com.quant.finance.decision.autotrade.CommandForwarder.Outcome;
import com.quant.finance.decision.autotrade.SignalScanner.Result;
import com.quant.finance.decision.autotrade.TradeCommand.Side;
import com.quant.finance.decision.engine.Timeframe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AutoTradeJobTest {

    private static final Instant NOW = Instant.parse("2026-09-21T15:00:30Z");
    private static final AutoTradeSettings ON = new AutoTradeSettings(true, Instant.parse("2026-09-21T14:00:00Z"), 1, OrderType.MKT,
            TimeInForce.DAY, List.of(), List.of(), 25);
    private static final Flip FLIP = new Flip("rsi", "AAPL", Timeframe.M15, Side.BUY, Instant.parse("2026-09-21T14:45:00Z"), 100);

    private AutoTradeConfigService config;
    private SignalScanner scanner;
    private CommandForwarder forwarder;
    private AutoTradeJob job;

    @BeforeEach
    void setUp() {
        config = mock(AutoTradeConfigService.class);
        scanner = mock(SignalScanner.class);
        forwarder = mock(CommandForwarder.class);
        job = new AutoTradeJob(config, scanner, forwarder, Clock.fixed(NOW, ZoneOffset.UTC), 30);
    }

    @AfterEach
    void tearDown() { job.shutdown(); }

    @Test
    void aSwitchedOffJobDoesNothingAtAll() {
        when(config.current()).thenReturn(AutoTradeSettings.defaults());
        job.tick();
        verifyNoInteractions(scanner, forwarder);
        assertNull(job.lastRun());
    }

    @Test
    void aSwitchedOnJobScansAndForwardsWhatItFoundAndRemembersTheRun() {
        when(config.current()).thenReturn(ON);
        when(scanner.scan(ON, NOW)).thenReturn(new Result(List.of(FLIP), 3, 1));
        when(forwarder.forward(List.of(FLIP))).thenReturn(new Outcome(1, 0, 0, 0));

        job.tick();

        assertEquals(new RunSummary(NOW, 3, 1, 1, 1, 0, 0, 0), job.lastRun());
        assertFalse(job.running(), "the pass is over");
    }

    @Test
    void aTickThatLookedAtNothingLeavesTheLastRunAlone() {
        when(config.current()).thenReturn(ON);
        when(scanner.scan(any(), any())).thenReturn(new Result(List.of(FLIP), 1, 0), Result.NONE);
        when(forwarder.forward(any())).thenReturn(new Outcome(1, 0, 0, 0), new Outcome(0, 0, 0, 0));
        job.tick();
        RunSummary first = job.lastRun();

        job.tick();
        assertSame(first, job.lastRun());
    }

    @Test
    void aFailureIsContainedSoTheScheduleKeepsRunning() {
        when(config.current()).thenReturn(ON);
        when(scanner.scan(any(), any())).thenThrow(new IllegalStateException("boom")).thenReturn(new Result(List.of(FLIP), 1, 0));
        when(forwarder.forward(any())).thenReturn(new Outcome(1, 0, 0, 0));

        assertDoesNotThrow(job::tick);
        assertFalse(job.running());
        assertNull(job.lastRun());
        verifyNoInteractions(forwarder);

        job.tick();
        assertNotNull(job.lastRun(), "the next tick works");
    }

    @Test
    void aBrokenConfigLookupIsContainedToo() {
        when(config.current()).thenThrow(new IllegalStateException("db down"));
        assertDoesNotThrow(job::tick);
    }

    @Test
    void theTickIntervalHasAFloor() {
        assertEquals(5, new AutoTradeJob(config, scanner, forwarder, Clock.fixed(NOW, ZoneOffset.UTC), 0).tickSeconds());
        assertEquals(30, job.tickSeconds());
    }
}
