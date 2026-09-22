package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.autotrade.AutoTradeSettings.OrderType;
import com.quant.finance.decision.autotrade.AutoTradeSettings.TimeInForce;
import com.quant.finance.decision.autotrade.SignalScanner.Result;
import com.quant.finance.decision.autotrade.TradeCommand.Side;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.autotrade.LiveDataSource;
import com.quant.finance.decision.service.StrategyService;
import com.quant.finance.decision.service.UniverseService;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SignalScannerTest {

    /** Monday 15:07:30: the last completed 15-minute bar opened at 14:45 and closed at 15:00; the 15:00 bar is still forming. */
    private static final Instant NOW = Instant.parse("2026-09-21T15:07:30Z");
    private static final Instant ENABLED = Instant.parse("2026-09-21T14:00:00Z");
    private static final Instant M15_BAR = Instant.parse("2026-09-21T14:45:00Z");

    private final List<String> fetched = new ArrayList<>();
    private final Set<String> failing = new HashSet<>();
    private final Map<String, TradingStrategy> enabled = new LinkedHashMap<>();
    private final Map<String, Timeframe> frames = new LinkedHashMap<>();
    private List<String> universeSymbols = List.of("AAPL");
    private StrategyService strategies;
    private SignalScanner scanner;

    @BeforeEach
    void setUp() {
        strategies = mock(StrategyService.class);
        when(strategies.getEnabledStrategies()).thenAnswer(i -> enabled);
        when(strategies.getStrategies(any())).thenAnswer(i -> {
            Map<String, TradingStrategy> out = new LinkedHashMap<>();
            for (String n : i.<Collection<String>>getArgument(0)) if (enabled.containsKey(n)) out.put(n, enabled.get(n));
            return out;
        });
        when(strategies.recommendedTimeframe(anyString())).thenAnswer(i -> frames.getOrDefault(i.<String>getArgument(0), Timeframe.M15));
        when(strategies.getParams(anyString())).thenReturn(Map.of());
        UniverseService universe = mock(UniverseService.class);
        when(universe.get()).thenAnswer(i -> universeSymbols);
        scanner = new SignalScanner(strategies, universe, data(), Runnable::run);
    }

    private LiveDataSource data() {
        return new LiveDataSource() {
            @Override public BarSeries bars(String symbol, Base base, int lookbackDays) {
                fetched.add(symbol + "|" + base);
                if (failing.contains(symbol)) throw new IllegalStateException("data down");
                return base == Base.DAY1 ? days(symbol, 60) : minutes(symbol, 2_500);
            }
            @Override public Map<String, Double> latestPrices(Collection<String> symbols) { return Map.of(); }
        };
    }

    /** {@code n} one-minute bars, the last one a minute before NOW, all at 100. */
    private static BarSeries minutes(String symbol, int n) {
        Instant[] d = new Instant[n];
        double[] px = new double[n];
        for (int i = 0; i < n; i++) { d[i] = NOW.minus(Duration.ofMinutes(n - i)); px[i] = 100; }
        return new BarSeries(symbol, d, px, px, px, px, px);
    }

    /** {@code n} daily bars stamped 04:00Z (US midnight), the last one today's, still forming. */
    private static BarSeries days(String symbol, int n) {
        Instant today = Instant.parse("2026-09-21T04:00:00Z");
        Instant[] d = new Instant[n];
        double[] px = new double[n];
        for (int i = 0; i < n; i++) { d[i] = today.minus(Duration.ofDays(n - 1 - i)); px[i] = 100; }
        return new BarSeries(symbol, d, px, px, px, px, px);
    }

    /** Wants {@code previous} on the second to last completed bar and {@code last} on the last. */
    private void strategy(String name, double previous, double last, Timeframe frame) {
        enabled.put(name, new TradingStrategy() {
            public String name() { return name; }
            public String category() { return "test"; }
            public String direction() { return "long_short"; }
            public String description() { return ""; }
            public Map<String, Double> defaultParams() { return Map.of(); }
            public double[] generateSignals(BarSeries b, Map<String, Double> params) {
                if (last == 999) throw new IllegalStateException("broken");
                double[] s = new double[b.size()];
                s[s.length - 2] = previous;
                s[s.length - 1] = last;
                return s;
            }
        });
        frames.put(name, frame);
    }

    private static AutoTradeSettings settings(Instant enabledSince, List<String> strategyNames, List<String> symbols) {
        return new AutoTradeSettings(true, enabledSince, 1, OrderType.MKT, TimeInForce.DAY, strategyNames, symbols, 25);
    }

    private Result scan() { return scanner.scan(settings(ENABLED, List.of(), List.of()), NOW); }

    @Test
    void aStrategyThatTurnedLongOnTheBarThatJustCompletedIsFound() {
        strategy("rsi", 0, 1, Timeframe.M15);
        Result result = scan();

        assertEquals(1, result.flips().size());
        Flip flip = result.flips().get(0);
        assertEquals("rsi", flip.strategy());
        assertEquals("AAPL", flip.symbol());
        assertEquals(Timeframe.M15, flip.timeframe());
        assertEquals(Side.BUY, flip.side());
        assertEquals(M15_BAR, flip.barOpen());
        assertEquals(100, flip.close());
        assertEquals(1, result.checked());
        assertEquals(0, result.errors());
    }

    @Test
    void aStrategyGoingShortIsASell() {
        strategy("macd", 1, -1, Timeframe.M15);
        assertEquals(Side.SELL, scan().flips().get(0).side());
    }

    @Test
    void standingFlatAndAlreadyTradedSignalsAreNotFlips() {
        strategy("standing", 1, 1, Timeframe.M15);
        strategy("flat", 1, 0, Timeframe.M15);
        strategy("idle", 0, 0, Timeframe.M15);
        assertTrue(scan().flips().isEmpty());
    }

    @Test
    void aFlipOnABarThatClosedBeforeTheJobWasSwitchedOnIsIgnored() {
        strategy("rsi", 0, 1, Timeframe.M15);
        Result result = scanner.scan(settings(Instant.parse("2026-09-21T15:02:00Z"), List.of(), List.of()), NOW);
        assertTrue(result.flips().isEmpty());
        assertEquals(1, result.checked(), "it was still looked at");
    }

    @Test
    void nothingIsFetchedAgainUntilTheNextBarIsDue() {
        strategy("rsi", 0, 1, Timeframe.M15);
        scan();
        int calls = fetched.size();

        Result later = scanner.scan(settings(ENABLED, List.of(), List.of()), NOW.plusSeconds(60));
        assertEquals(calls, fetched.size(), "a second look at the same bar would only repeat the work");
        assertEquals(0, later.checked());
        assertTrue(later.flips().isEmpty());
    }

    @Test
    void strategiesOfDifferentTimeframesShareOneFetchAndEachIsReadOnItsOwnBars() {
        strategy("fast", 0, 1, Timeframe.M15);
        strategy("slow", 0, -1, Timeframe.H1);
        Result result = scan();

        assertEquals(List.of("AAPL|MIN1"), fetched, "both frames are built from the same 1-minute bars");
        assertEquals(2, result.checked());
        Map<String, Flip> byStrategy = new LinkedHashMap<>();
        result.flips().forEach(f -> byStrategy.put(f.strategy(), f));
        assertEquals(Timeframe.M15, byStrategy.get("fast").timeframe());
        assertEquals(Timeframe.H1, byStrategy.get("slow").timeframe());
        assertEquals(Instant.parse("2026-09-21T14:00:00Z"), byStrategy.get("slow").barOpen(), "the last completed hour opened at 14:00");
    }

    @Test
    void aDailyStrategyReadsDailyBars() {
        strategy("swing", 0, 1, Timeframe.D1);
        Result result = scanner.scan(settings(Instant.parse("2026-09-20T00:00:00Z"), List.of(), List.of()), NOW);

        assertEquals(List.of("AAPL|DAY1"), fetched);
        assertEquals(Instant.parse("2026-09-20T04:00:00Z"), result.flips().get(0).barOpen(), "yesterday's bar is the last completed one");
    }

    @Test
    void oneBrokenStrategyDoesNotStopTheOthers() {
        strategy("broken", 0, 999, Timeframe.M15);
        strategy("good", 0, 1, Timeframe.M15);
        Result result = scan();

        assertEquals(List.of("good"), result.flips().stream().map(Flip::strategy).toList());
        assertEquals(1, result.errors());
    }

    @Test
    void aSymbolWhoseDataCannotBeFetchedIsAnErrorAndTheOthersCarryOn() {
        universeSymbols = List.of("AAPL", "MSFT");
        failing.add("AAPL");
        strategy("rsi", 0, 1, Timeframe.M15);
        Result result = scan();

        assertEquals(List.of("MSFT"), result.flips().stream().map(Flip::symbol).toList());
        assertEquals(1, result.errors());
    }

    @Test
    void notEnoughHistoryIsNotAnErrorAndProducesNoFlip() {
        strategy("rsi", 0, 1, Timeframe.M15);
        SignalScanner shortHistory = new SignalScanner(strategies, universeOf("AAPL"), new LiveDataSource() {
            @Override public BarSeries bars(String symbol, Base base, int lookbackDays) { return minutes(symbol, 60); }
            @Override public Map<String, Double> latestPrices(Collection<String> symbols) { return Map.of(); }
        }, Runnable::run);
        Result result = shortHistory.scan(settings(ENABLED, List.of(), List.of()), NOW);
        assertTrue(result.flips().isEmpty());
        assertEquals(0, result.errors());
    }

    @Test
    void onlyTheChosenStrategiesAndSymbolsAreScanned() {
        universeSymbols = List.of("AAPL", "MSFT", "TSLA");
        strategy("rsi", 0, 1, Timeframe.M15);
        strategy("macd", 0, 1, Timeframe.M15);
        Result result = scanner.scan(settings(ENABLED, List.of("macd"), List.of("MSFT")), NOW);

        assertEquals(List.of("MSFT|MIN1"), fetched);
        assertEquals(1, result.flips().size());
        assertEquals("macd", result.flips().get(0).strategy());
    }

    @Test
    void noStrategiesInScopeMeansNothingIsFetched() {
        assertEquals(0, scan().checked());
        assertTrue(fetched.isEmpty());
    }

    private static UniverseService universeOf(String... symbols) {
        UniverseService universe = mock(UniverseService.class);
        when(universe.get()).thenReturn(List.of(symbols));
        return universe;
    }
}
