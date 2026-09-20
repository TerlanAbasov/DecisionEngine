package com.quant.finance.decision.live;

import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.live.LiveDataSource.Base;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SignalEvaluatorTest {
    private static final Instant NOW = Instant.parse("2026-09-21T15:07:00Z");

    private static BarSeries minutes(int n, Instant lastBar) {
        Instant[] d = new Instant[n];
        double[] px = new double[n];
        for (int i = 0; i < n; i++) { d[i] = lastBar.minus(Duration.ofMinutes(n - 1 - i)); px[i] = 100 + i; }
        return new BarSeries("X", d, px, px, px, px, px);
    }

    private static TradingStrategy lastIs(double v) {
        return new TradingStrategy() {
            public String name() { return "t"; }
            public String category() { return "t"; }
            public String direction() { return "long_short"; }
            public String description() { return ""; }
            public Map<String, Double> defaultParams() { return Map.of(); }
            public double[] generateSignals(BarSeries b, Map<String, Double> p) {
                double[] s = new double[b.size()];
                Arrays.fill(s, 0);
                s[s.length - 1] = v;
                return s;
            }
        };
    }

    @Test
    void nativeIsLiftedToFifteenMinutesAndTheBaseFollowsTheFrame() {
        assertEquals(Timeframe.M15, SignalEvaluator.liveFrame(Timeframe.NATIVE));
        assertEquals(Timeframe.H1, SignalEvaluator.liveFrame(Timeframe.H1));
        assertEquals(Base.MIN1, SignalEvaluator.baseFor(Timeframe.M15));
        assertEquals(Base.MIN1, SignalEvaluator.baseFor(Timeframe.H12));
        assertEquals(Base.DAY1, SignalEvaluator.baseFor(Timeframe.D1));
        assertEquals(Base.DAY1, SignalEvaluator.baseFor(Timeframe.W1));
    }

    @Test
    void lookbackDaysGrowWithTheFrameAndAreCapped() {
        int m15 = SignalEvaluator.lookbackDays(Timeframe.M15, 300);
        int h1 = SignalEvaluator.lookbackDays(Timeframe.H1, 300);
        assertTrue(m15 > 10 && m15 < h1, m15 + " vs " + h1);
        assertEquals(120, SignalEvaluator.lookbackDays(Timeframe.H12, 2000), "intraday is capped");
        assertTrue(SignalEvaluator.lookbackDays(Timeframe.D1, 300) >= 450);
        assertEquals(1500, SignalEvaluator.lookbackDays(Timeframe.W1, 2000), "daily-based is capped");
    }

    @Test
    void theBarStillFormingIsDroppedAndACompletedOneIsKept() {
        BarSeries b = minutes(600, NOW.truncatedTo(java.time.temporal.ChronoUnit.MINUTES));
        BarSeries m15 = com.quant.finance.decision.engine.BarResampler.resample(b, Timeframe.M15);
        BarSeries done = SignalEvaluator.completedOnly(m15, Timeframe.M15, NOW);            // 15:07: the 15:00 bar is forming
        assertEquals(m15.size() - 1, done.size());
        assertEquals(Instant.parse("2026-09-21T14:45:00Z"), done.date[done.size() - 1]);
        BarSeries later = SignalEvaluator.completedOnly(m15, Timeframe.M15, NOW.plusSeconds(15 * 60));   // 15:22: 15:00 is complete
        assertEquals(m15.size(), later.size());
        assertEquals(0, SignalEvaluator.completedOnly(minutes(0, NOW), Timeframe.M15, NOW).size());
    }

    @Test
    void theLastSignalIsTheTargetAfterTheLastCompletedBar() {
        BarSeries b = minutes(900, NOW.truncatedTo(java.time.temporal.ChronoUnit.MINUTES));
        assertEquals(1.0, SignalEvaluator.lastSignal(lastIs(1), Map.of(), b, Timeframe.M15, NOW));
        assertEquals(-1.0, SignalEvaluator.lastSignal(lastIs(-1), Map.of(), b, Timeframe.M15, NOW));
        assertEquals(1.0, SignalEvaluator.lastSignal(lastIs(5), Map.of(), b, Timeframe.M15, NOW), "clamped to full size");
        assertEquals(0.0, SignalEvaluator.lastSignal(lastIs(Double.NaN), Map.of(), b, Timeframe.M15, NOW));
    }

    @Test
    void tooLittleHistoryMeansNoSignalRatherThanAGuess() {
        BarSeries few = minutes(200, NOW.truncatedTo(java.time.temporal.ChronoUnit.MINUTES));   // ~13 completed 15-min bars
        assertNull(SignalEvaluator.lastSignal(lastIs(1), Map.of(), few, Timeframe.M15, NOW));
        assertNull(SignalEvaluator.lastSignal(lastIs(1), Map.of(), minutes(0, NOW), Timeframe.M15, NOW));
    }

    @Test
    void aStrategyReturningTheWrongNumberOfSignalsIsAnError() {
        TradingStrategy broken = new TradingStrategy() {
            public String name() { return "broken"; }
            public String category() { return "t"; }
            public String direction() { return "long_short"; }
            public String description() { return ""; }
            public Map<String, Double> defaultParams() { return Map.of(); }
            public double[] generateSignals(BarSeries b, Map<String, Double> p) { return new double[3]; }
        };
        BarSeries b = minutes(900, NOW.truncatedTo(java.time.temporal.ChronoUnit.MINUTES));
        assertThrows(IllegalStateException.class, () -> SignalEvaluator.lastSignal(broken, Map.of(), b, Timeframe.M15, NOW));
    }
}
