package com.quant.finance.decision.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.quant.finance.decision.data.SyntheticData;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The equity curve is the source of truth for every headline number, so the trade log must be a
 * faithful decomposition of it: the trades' returns have to add up to the run's total return.
 */
class BacktesterTradeAccountingTest {

    private static final LocalDate END = LocalDate.of(2026, 9, 1);

    /** Fixed, reproducible target positions in {-1,-0.5,0,0.5,1}, held for random stretches. */
    private static TradingStrategy scripted(long seed, boolean fractional) {
        return new TradingStrategy() {
            public String name() {
                return "scripted";
            }

            public String category() {
                return "test";
            }

            public String direction() {
                return "long_short";
            }

            public String description() {
                return "test";
            }

            public Map<String, Double> defaultParams() {
                return Map.of();
            }

            public double[] generateSignals(BarSeries series, Map<String, Double> params) {
                Random random = new Random(seed);
                double[] out = new double[series.size()];
                double cur = 0;
                int hold = 0;
                for (int i = 0; i < out.length; i++) {
                    if (hold-- <= 0) {
                        int draw = random.nextInt(fractional ? 5 : 3);
                        cur = fractional ? (draw - 2) * 0.5 : (draw - 1);
                        hold = 1 + random.nextInt(12);
                    }
                    out[i] = cur;
                }
                return out;
            }
        };
    }

    private static BacktestConfig cfg(double sl, double tp, int warmup) {
        return BacktestConfig.builder()
                .capital(100_000)
                .commissionBps(1)
                .slippageBps(2)
                .allowShort(true)
                .execLag(1)
                .positionSize(1)
                .stopLossPct(sl)
                .takeProfitPct(tp)
                .warmupBars(warmup)
                .build();
    }

    private static double totalReturn(BacktestOutput output, BacktestConfig config) {
        return output.equity[output.equity.length - 1] / config.capital - 1;
    }

    private static double sumTrades(BacktestOutput output) {
        double sum = 0;
        for (TradeResult t : output.trades) {
            sum += t.returnPct;
        }
        return sum;
    }

    @Test
    void tradesAddUpToTheEquityCurveTotalReturn() {
        BarSeries bars = SyntheticData.generate("MU", 3, END);
        for (boolean fractional : new boolean[] {false, true}) {
            BacktestConfig config = cfg(0, 0, 0);
            BacktestOutput output = new Backtester(false).runSingle(bars, scripted(7, fractional), null, config);
            assertFalse(output.trades.isEmpty());
            assertEquals(
                    totalReturn(output, config),
                    sumTrades(output),
                    1e-9,
                    "sum of trade returns must equal the equity curve's total return (fractional=" + fractional + ")");
        }
    }

    @Test
    void tradesStillReconcileWithStopLossAndTakeProfit() {
        BarSeries bars = SyntheticData.generate("SNDK", 3, END);
        BacktestConfig config = cfg(1.5, 3.0, 0);
        BacktestOutput output = new Backtester(false).runSingle(bars, scripted(11, false), null, config);
        assertFalse(output.trades.isEmpty());
        assertEquals(totalReturn(output, config), sumTrades(output), 1e-9);
    }

    // ---- exact semantics on a tiny hand-computed series ----------------------------------

    /** Daily bars on consecutive UTC days from 2026-01-01 with the given closes. */
    private static BarSeries bars(String symbol, double... closes) {
        int length = closes.length;
        Instant[] dates = new Instant[length];
        for (int i = 0; i < length; i++) {
            dates[i] = LocalDate.of(2026, 1, 1)
                    .plusDays(i)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        }
        double[] volume = new double[length];
        Arrays.fill(volume, 1000);
        return new BarSeries(symbol, dates, closes.clone(), closes.clone(), closes.clone(), closes.clone(), volume);
    }

    private static TradingStrategy fixed(double... target) {
        return new TradingStrategy() {
            public String name() {
                return "fixed";
            }

            public String category() {
                return "test";
            }

            public String direction() {
                return "long_short";
            }

            public String description() {
                return "test";
            }

            public Map<String, Double> defaultParams() {
                return Map.of();
            }

            public double[] generateSignals(BarSeries series, Map<String, Double> params) {
                return target.clone();
            }
        };
    }

    private static BacktestConfig costly() {
        // 10 + 20 bps => 30 bps per side (0.003), large enough to be unmistakable in assertions
        return BacktestConfig.builder()
                .capital(100_000)
                .commissionBps(10)
                .slippageBps(20)
                .allowShort(true)
                .execLag(1)
                .positionSize(1)
                .build();
    }

    @Test
    void aClosedTradeIsFilledAtTheClosesTheEquityAccountingUses() {
        // signal 1,1 on bars 1,2 -> executed (lag 1) held over bars 2 and 3
        BarSeries series = bars("X", 100, 101, 102, 103, 102, 101);
        BacktestOutput output = new Backtester(false).runSingle(series, fixed(0, 1, 1, 0, 0, 0), null, costly());
        assertEquals(1, output.trades.size());
        TradeResult trade = output.trades.get(0);
        assertEquals("LONG", trade.side);
        assertEquals(101, trade.entryPx, 1e-12, "entered at the close of the bar before the first held bar");
        assertEquals(103, trade.exitPx, 1e-12, "exited at the close of the last held bar");
        assertEquals(series.date[2], trade.entryDate);
        assertEquals(series.date[4], trade.exitDate);
        assertEquals(2, trade.bars);
        assertEquals(1.0, trade.exposure, 1e-12);
        assertFalse(trade.open);
        double gross = (102.0 / 101 - 1) + (103.0 / 102 - 1);
        assertEquals(gross, trade.grossReturn, 1e-12);
        assertEquals(2 * 0.003, trade.cost, 1e-12, "one cost on entry, one on exit");
        assertEquals(gross - 2 * 0.003, trade.netReturn, 1e-12);
        assertEquals(trade.netReturn, trade.returnPct, 1e-12, "a lone symbol's contribution is its own net return");
        assertEquals(totalReturn(output, costly()), trade.netReturn, 1e-12);
    }

    @Test
    void aFlipSplitsTheCostBetweenBothTradesAndTheOpenTradeHasNoExitCost() {
        // executed: flat, long over bars 1-3, flip to short at bar 4, short over bars 4-5 (still open)
        BarSeries series = bars("X", 100, 101, 102, 103, 104, 105);
        BacktestOutput output = new Backtester(false).runSingle(series, fixed(1, 1, 1, -1, -1, -1), null, costly());
        assertEquals(2, output.trades.size());
        TradeResult lg = output.trades.get(0);
        TradeResult sh = output.trades.get(1);

        assertEquals("LONG", lg.side);
        assertEquals(100, lg.entryPx, 1e-12);
        assertEquals(103, lg.exitPx, 1e-12);
        assertEquals(2 * 0.003, lg.cost, 1e-12, "entry + the exit half of the flip");
        assertFalse(lg.open);

        assertEquals("SHORT", sh.side);
        assertEquals(103, sh.entryPx, 1e-12, "the flip happens at one price: the long's exit is the short's entry");
        assertEquals(105, sh.exitPx, 1e-12, "open trade is marked at the last close");
        assertEquals(series.date[5], sh.exitDate);
        assertEquals(0.003, sh.cost, 1e-12, "only the entry half of the flip; no exit cost while open");
        assertTrue(sh.open);
        assertEquals(2, sh.bars);

        assertEquals(totalReturn(output, costly()), lg.netReturn + sh.netReturn, 1e-12);
    }

    // ---- multi-symbol portfolio invariants ------------------------------------------------

    @Test
    void perSymbolResultsAndTheBlendAllReconcile() {
        List<BarSeries> data = List.of(
                SyntheticData.generate("MU", 3, END),
                SyntheticData.generate("SNDK", 3, END),
                SyntheticData.generate("NBIS", 3, END));
        BacktestConfig config = cfg(2, 0, 0);
        TradingStrategy strat = scripted(3, true);
        Backtester bt = new Backtester(false);
        BacktestOutput output = bt.runPortfolio(data, strat, null, config);

        assertEquals(List.of("MU", "SNDK", "NBIS"), List.copyOf(output.symbolResults.keySet()));
        double weightedSum = 0;
        for (BarSeries b : data) {
            SymbolResult sr = output.symbolResults.get(b.symbol);
            double net = 0;
            for (TradeResult t : output.trades) {
                if (t.symbol.equals(b.symbol)) {
                    net += t.netReturn;
                    weightedSum += t.returnPct;
                }
            }
            assertEquals(
                    sr.metrics().get("totalReturnPct"),
                    100 * net,
                    0.006,
                    b.symbol + ": the symbol's trades must add up to its own total return");
            assertEquals(sr.metrics().get("totalReturnPct"), output.symbolReturnsPct.get(b.symbol), 0.0);
            // the portfolio's per-symbol numbers are exactly what a lone run of that symbol gives
            assertEquals(
                    bt.runSingle(b, strat, null, config).metrics,
                    sr.metrics(),
                    b.symbol + " metrics differ from a solo run");
            double yearSum = sr.yearlyReturnsPct().values().stream()
                    .mapToDouble(Double::doubleValue)
                    .sum();
            assertEquals(
                    sr.metrics().get("totalReturnPct"), yearSum, 0.05, b.symbol + ": years must add up to the total");
        }
        assertEquals(
                totalReturn(output, config),
                weightedSum,
                1e-9,
                "weighted contributions must add up to the portfolio total");
        double portfolioYears = output.yearlyReturnsPct.values().stream()
                .mapToDouble(Double::doubleValue)
                .sum();
        assertEquals(output.metrics.get("totalReturnPct"), portfolioYears, 0.05);
        assertTrue(output.yearlyReturnsPct.size() >= 3, "3 years of data spans at least 3 calendar years");
        List<String> years = new ArrayList<>(output.yearlyReturnsPct.keySet());
        assertEquals(years.stream().sorted().toList(), years, "years are reported oldest first");
    }

    /** Same listing span, but a random share of the interior bars missing — like a thinly traded name's intraday bars. */
    private static BarSeries withGaps(BarSeries series, long seed, double dropFraction) {
        Random random = new Random(seed);
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < series.size(); i++) {
            if (i == 0 || i == series.size() - 1 || random.nextDouble() >= dropFraction) {
                keep.add(i);
            }
        }
        int keptCount = keep.size();
        Instant[] dates = new Instant[keptCount];
        double[] open = new double[keptCount];
        double[] high = new double[keptCount];
        double[] low = new double[keptCount];
        double[] close = new double[keptCount];
        double[] volume = new double[keptCount];
        for (int k = 0; k < keptCount; k++) {
            int sourceBar = keep.get(k);
            dates[k] = series.date[sourceBar];
            open[k] = series.open[sourceBar];
            high[k] = series.high[sourceBar];
            low[k] = series.low[sourceBar];
            close[k] = series.close[sourceBar];
            volume[k] = series.volume[sourceBar];
        }
        return new BarSeries(series.symbol, dates, open, high, low, close, volume);
    }

    private static double symbolNet(BacktestOutput output, String symbol) {
        double net = 0;
        for (TradeResult t : output.trades) {
            if (t.symbol.equals(symbol)) {
                net += t.netReturn;
            }
        }
        return net;
    }

    @Test
    void portfolioTotalIsTheMeanOfTheSymbolTotalsWhenEverySymbolIsListedThroughout() {
        // Regression: the blend averaged only over the symbols that had a bar at a timestamp, so a
        // symbol with sparse bars got extra weight and the "portfolio" beat every one of its symbols
        // (801% vs a best symbol of 753%). Each symbol is a fixed 1/N sleeve for as long as it is listed.
        List<BarSeries> data = List.of(
                withGaps(SyntheticData.generate("AAA", 2, END), 1, 0.6),
                withGaps(SyntheticData.generate("BBB", 2, END), 2, 0.3),
                withGaps(SyntheticData.generate("CCC", 2, END), 3, 0.85),
                SyntheticData.generate("DDD", 2, END));
        for (BacktestConfig c : List.of(cfg(0, 0, 0), cfg(2, 4, 0))) {
            for (boolean fractional : new boolean[] {false, true}) {
                BacktestOutput output = new Backtester(false).runPortfolio(data, scripted(21, fractional), null, c);
                double mean = 0;
                double best = Double.NEGATIVE_INFINITY;
                double worst = Double.POSITIVE_INFINITY;
                for (BarSeries b : data) {
                    double net = symbolNet(output, b.symbol);
                    mean += net / data.size();
                    best = Math.max(best, net);
                    worst = Math.min(worst, net);
                }
                String what = "sl=" + c.stopLossPct + " fractional=" + fractional;
                assertEquals(
                        mean, totalReturn(output, c), 1e-9, what + ": total must be the mean of the symbols' totals");
                assertEquals(
                        totalReturn(output, c),
                        sumTrades(output),
                        1e-9,
                        what + ": contributions must add up to the total");
                assertTrue(
                        totalReturn(output, c) <= best + 1e-12 && totalReturn(output, c) >= worst - 1e-12,
                        what + ": an equal-weight blend cannot beat its best symbol or trail its worst");
            }
        }
    }

    @Test
    void gapsInsideASymbolsListingKeepItsWeightAndItsPositionInTheBlend() {
        // one symbol has bars on every other day only; the blend must still weigh it 1/2 on the days it is
        // missing, and carry its position across the gap so exposure isn't understated
        BarSeries full = SyntheticData.generate("FULL", 1, END);
        BarSeries sparse = withGaps(SyntheticData.generate("SPARSE", 1, END), 5, 0.7);
        BacktestConfig config = cfg(0, 0, 0);
        BacktestOutput output =
                new Backtester(false).runPortfolio(List.of(full, sparse), scripted(8, false), null, config);
        assertEquals(full.size(), output.dates.length, "the union axis is the fuller symbol's bars");
        double mean = (symbolNet(output, "FULL") + symbolNet(output, "SPARSE")) / 2;
        assertEquals(mean, totalReturn(output, config), 1e-9);

        // an always-long book holds the same position throughout, so its blended position must stay put
        // across the gaps — turnover is just the one-off build-up (~100% of capital over the ~1y run)
        TradingStrategy alwaysLong = new TradingStrategy() {
            public String name() {
                return "always_long";
            }

            public String category() {
                return "test";
            }

            public String direction() {
                return "long_only";
            }

            public String description() {
                return "test";
            }

            public Map<String, Double> defaultParams() {
                return Map.of();
            }

            public double[] generateSignals(BarSeries series, Map<String, Double> params) {
                double[] out = new double[series.size()];
                Arrays.fill(out, 1.0);
                return out;
            }
        };
        BacktestOutput held = new Backtester(false).runPortfolio(List.of(full, sparse), alwaysLong, null, config);
        double turnover = held.metrics.get("annTurnoverPct");
        assertTrue(
                turnover > 50 && turnover < 200,
                "position must be carried across gaps, turnover was " + turnover + "%");
    }

    @Test
    void theProgressCallbackFiresOncePerSymbolWithAndWithoutAPool() throws Exception {
        List<BarSeries> data = List.of(
                SyntheticData.generate("AAA", 1, END),
                SyntheticData.generate("BBB", 1, END),
                SyntheticData.generate("CCC", 1, END));
        BacktestConfig config = cfg(0, 0, 0);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            for (java.util.concurrent.Executor ex : new java.util.concurrent.Executor[] {null, pool}) {
                java.util.List<String> seen = java.util.Collections.synchronizedList(new ArrayList<>());
                BacktestOutput withCallback =
                        new Backtester(false).runPortfolio(data, scripted(2, false), null, config, ex, true, seen::add);
                assertEquals(
                        List.of("AAA", "BBB", "CCC"), seen.stream().sorted().toList());
                BacktestOutput without =
                        new Backtester(false).runPortfolio(data, scripted(2, false), null, config, ex, true);
                assertEquals(without.metrics, withCallback.metrics, "reporting progress must not change the result");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void yearlyReturnsSplitOnTheUtcCalendarYear() {
        Instant[] dates = {
            Instant.parse("2025-12-31T23:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-06-01T12:00:00Z")
        };
        Map<String, Double> values = PerformanceMetrics.yearlyReturnsPct(new double[] {0.10, 0.02, 0.03}, dates);
        assertEquals(List.of("2025", "2026"), List.copyOf(values.keySet()));
        assertEquals(10.0, values.get("2025"), 1e-12);
        assertEquals(5.0, values.get("2026"), 1e-12);
    }

    @Test
    void warmupOnlyLeavesTheOneUnattributedExitCostOut() {
        BarSeries bars = SyntheticData.generate("MU", 3, END);
        BacktestConfig config = cfg(0, 0, 40);
        BacktestOutput output = new Backtester(false).runSingle(bars, scripted(5, false), null, config);
        double slack = 0.003; // at most one exit cost (3 bps here) can fall outside every trade
        assertEquals(totalReturn(output, config), sumTrades(output), slack);
    }

    @Test
    void skippingSymbolDetailsChangesNothingAboutThePortfolio() {
        List<BarSeries> data = List.of(SyntheticData.generate("MU", 2, END), SyntheticData.generate("NBIS", 2, END));
        BacktestConfig config = cfg(2, 4, 0);
        TradingStrategy strat = scripted(9, false);
        BacktestOutput full = new Backtester(false).runPortfolio(data, strat, null, config, null, true);
        BacktestOutput lean = new Backtester(false).runPortfolio(data, strat, null, config, null, false);
        assertEquals(full.metrics, lean.metrics);
        assertArrayEquals(full.equity, lean.equity, 0.0);
        assertEquals(full.trades.size(), lean.trades.size());
        assertEquals(full.yearlyReturnsPct, lean.yearlyReturnsPct);
        assertEquals(2, full.symbolResults.size());
        assertTrue(lean.symbolResults.isEmpty() && lean.symbolReturnsPct.isEmpty());
    }

    @Test
    void symbolsWithDifferentHistoryLengthsEachKeepTheirOwnStandaloneResult() {
        // a newly listed ticker has fewer bars than the others; the blend spans the union of dates
        BarSeries longer = SyntheticData.generate("MU", 3, END);
        BarSeries shorter = SyntheticData.generate("SNDK", 1, END);
        BacktestConfig config = cfg(0, 0, 0);
        TradingStrategy strat = scripted(4, false);
        Backtester bt = new Backtester(false);
        BacktestOutput output = bt.runPortfolio(List.of(longer, shorter), strat, null, config);
        for (BarSeries b : List.of(longer, shorter)) {
            SymbolResult sr = output.symbolResults.get(b.symbol);
            assertEquals(
                    bt.runSingle(b, strat, null, config).metrics, sr.metrics(), b.symbol + " must match a solo run");
            double net = 0;
            for (TradeResult t : output.trades) {
                if (t.symbol.equals(b.symbol)) {
                    net += t.netReturn;
                }
            }
            assertEquals(sr.metrics().get("totalReturnPct"), 100 * net, 0.006);
        }
        assertTrue(output.symbolResults.get("MU").yearlyReturnsPct().size()
                > output.symbolResults.get("SNDK").yearlyReturnsPct().size() - 1);
    }

    @Test
    void blendedContributionsAddUpToThePortfolioTotalWhenHistoriesAreStaggered() {
        // Where a symbol has no bar the blend averages over fewer symbols, so a symbol's weight is
        // not a fixed 1/N — and can change while one of its trades is open. Σ contributions must
        // still equal the blended total (regression: a fixed 1/N under-counted by ~5% of the return).
        BarSeries mu = SyntheticData.generate("MU", 3, END);
        BarSeries sndk = SyntheticData.generate("SNDK", 2, END);
        BarSeries nbis = SyntheticData.generate("NBIS", 1, END);
        for (boolean fractional : new boolean[] {false, true}) {
            for (BacktestConfig c : List.of(cfg(0, 0, 0), cfg(2, 4, 0), cfg(0, 0, 0))) {
                BacktestOutput output =
                        new Backtester(false).runPortfolio(List.of(mu, sndk, nbis), scripted(11, fractional), null, c);
                assertEquals(
                        totalReturn(output, c),
                        sumTrades(output),
                        1e-9,
                        "fractional=" + fractional + " sl=" + c.stopLossPct
                                + ": contributions must add up to the blended total");
                assertTrue(output.trades.stream().anyMatch(t -> t.symbol.equals("NBIS")));
            }
        }
    }

    @Test
    void newlyAddedStrategiesActuallyTradeOnRealisticData() {
        // guards against a strategy whose signal logic is silently dead (e.g. NaN poisoning)
        BacktestConfig config = cfg(0, 0, 0);
        for (String name : new String[] {"wave_trend", "support_resistance_bounce"}) {
            TradingStrategy strat = com.quant.finance.decision.strategy.impl.StrategyCatalog.all().stream()
                    .filter(x -> x.name().equals(name))
                    .findFirst()
                    .orElseThrow();
            int trades = 0;
            for (String sym : new String[] {"MU", "SNDK", "NBIS", "RKLB"}) {
                trades += new Backtester(false)
                        .runSingle(SyntheticData.generate(sym, 6.5, END), strat, null, config)
                        .trades
                        .size();
            }
            assertTrue(trades > 0, name + " never traded on any synthetic series — signal logic is dead");
        }
    }

    @Test
    void waveTrendIsFiniteAfterWarmupAndSwingsAcrossTheZones() {
        BarSeries series = SyntheticData.generate("MU", 6.5, END);
        double[][] wt = com.quant.finance.decision.strategy.Indicators.waveTrend(series, 10, 21);
        int warmup = 10 + 10 + 21 + 4; // esa, |ap-esa| smoothing, WT1 smoothing, WT2 SMA
        double max = Double.NEGATIVE_INFINITY;
        double min = Double.POSITIVE_INFINITY;
        for (int i = warmup; i < series.size(); i++) {
            assertFalse(Double.isNaN(wt[0][i]) || Double.isNaN(wt[1][i]), "NaN at bar " + i + " (past warm-up)");
            max = Math.max(max, wt[0][i]);
            min = Math.min(min, wt[0][i]);
        }
        assertTrue(max > 40 && min < -40, "WT1 should swing through the +-60 zones; range was " + min + ".." + max);
    }
}
