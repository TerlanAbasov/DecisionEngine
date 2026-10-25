package com.quant.finance.decision.engine;

import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import com.quant.finance.decision.strategy.impl.PairsStrategy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/** Vectorised, next-bar backtesting engine. */
@Slf4j
public final class Backtester {

    /** Emit a per-(strategy × stock) INFO line while running a portfolio. */
    private final boolean tracePerSymbol;

    public Backtester() {
        this(true);
    }

    public Backtester(boolean tracePerSymbol) {
        this.tracePerSymbol = tracePerSymbol;
    }

    /**
     * Cap on one bar's price move fed into P&amp;L: a liquid instrument doesn't move ±75% bar to bar, so more is a bad tick, a data gap or an
     * unadjusted split, and unclamped it would detonate the equity path.
     */
    private static final double MAX_BAR_RETURN = 0.75;

    /** Close-to-close return for bar {@code i}, guarded against zero/negative prices and clamped. */
    private static double barReturn(double[] close, int bar) {
        if (bar <= 0) {
            return 0;
        }
        double previousPrice = close[bar - 1];
        double price = close[bar];
        if (!(previousPrice > 0) || !(price > 0)) {
            return 0;
        }
        double barReturn = price / previousPrice - 1;
        if (barReturn > MAX_BAR_RETURN) {
            return MAX_BAR_RETURN;
        }
        if (barReturn < -MAX_BAR_RETURN) {
            return -MAX_BAR_RETURN;
        }
        return barReturn;
    }

    /** Per-symbol computed series aligned to the symbol's own dates. */
    private record Series(
            Instant[] dates,
            double[] close,
            double[] net,
            double[] bench,
            double[] position,
            double[] absPosition,
            List<TradeResult> trades) {}

    private Series computedAndReported(
            BarSeries bars,
            TradingStrategy strategy,
            Map<String, Double> params,
            BacktestConfig config,
            Consumer<String> onSymbolComputed) {
        Series series = computeOne(bars, strategy, params, config);
        if (onSymbolComputed != null) {
            onSymbolComputed.accept(bars.symbol);
        }
        return series;
    }

    /** {@link #computeSeries} plus a per-(strategy × stock) trace line. */
    private Series computeOne(
            BarSeries bars, TradingStrategy strategy, Map<String, Double> params, BacktestConfig config) {
        long startMillis = System.currentTimeMillis();
        Series series = computeSeries(bars, strategy, params, config);
        if (tracePerSymbol && log.isInfoEnabled()) {
            double totalReturn = 0;
            for (double barNet : series.net) {
                if (!Double.isNaN(barNet)) {
                    totalReturn += barNet;
                }
            }
            log.info(
                    "Backtest: {} × {} — {} bars, {} trades, ret {}% ({} ms)",
                    strategy.name(),
                    bars.symbol,
                    bars.size(),
                    series.trades.size(),
                    Math.round(totalReturn * 10000) / 100.0,
                    System.currentTimeMillis() - startMillis);
        }
        return series;
    }

    private Series computeSeries(
            BarSeries bars, TradingStrategy strategy, Map<String, Double> params, BacktestConfig config) {
        int length = bars.size();
        double[] target = strategy.generateSignals(bars, params);
        for (int i = 0; i < length; i++) {
            if (!config.allowShort && target[i] < 0) {
                target[i] = 0;
            }
            target[i] *= config.positionSize; // scale gross exposure
        }

        double[] position = new double[length]; // executed position (shifted by execLag)
        for (int i = 0; i < length; i++) {
            int signalBar = i - config.execLag;
            position[i] = signalBar >= 0 ? target[signalBar] : 0;
        }
        position = applyRiskExits(position, bars.close, config); // stop-loss / take-profit force-exits

        double[] net = new double[length];
        double[] bench = new double[length];
        double[] absPosition = new double[length];
        double costRate = (config.commissionBps + config.slippageBps) / 1e4;
        for (int i = 0; i < length; i++) {
            double barReturn = barReturn(bars.close, i);
            double previousPosition = i > 0 ? position[i - 1] : 0;
            double turnover = Math.abs(position[i] - previousPosition);
            net[i] = position[i] * barReturn - turnover * costRate;
            bench[i] = barReturn;
            absPosition[i] = Math.abs(position[i]);
            if (i < config.warmupBars) { // ignore burn-in P&L
                net[i] = 0;
                absPosition[i] = 0;
            }
        }
        List<TradeResult> trades =
                extractTrades(position, bars.close, bars.date, bars.symbol, costRate, config.warmupBars, null);
        return new Series(bars.date, bars.close, net, bench, position, absPosition, trades);
    }

    /**
     * A trade whose unrealised return breaches -stopLossPct or +takeProfitPct is flattened for the rest of that bar and stays flat
     * until the strategy stops asking for that same direction (a fresh signal or a flip re-arms entry).
     */
    private double[] applyRiskExits(double[] requested, double[] close, BacktestConfig config) {
        if (config.stopLossPct <= 0 && config.takeProfitPct <= 0) {
            return requested;
        }
        double stopLoss = config.stopLossPct / 100.0;
        double takeProfit = config.takeProfitPct / 100.0;
        double[] out = requested.clone();
        int currentDirection = 0;
        int blockedDirection = 0;
        double entryPrice = 0;
        for (int i = 0; i < requested.length; i++) {
            double wanted = requested[i];
            int wantedDirection = (int) Math.signum(wanted);
            if (blockedDirection != 0 && wantedDirection != blockedDirection) {
                blockedDirection = 0;
            }
            if (blockedDirection != 0) {
                out[i] = 0;
                currentDirection = 0;
                continue;
            }
            if (wantedDirection != currentDirection) {
                currentDirection = wantedDirection;
                entryPrice = close[i];
                out[i] = wanted;
                continue;
            }
            if (currentDirection != 0) {
                double tradeReturn = currentDirection * (close[i] / entryPrice - 1);
                if ((stopLoss > 0 && tradeReturn <= -stopLoss) || (takeProfit > 0 && tradeReturn >= takeProfit)) {
                    out[i] = 0;
                    blockedDirection = currentDirection;
                    currentDirection = 0;
                } else {
                    out[i] = wanted;
                }
            }
        }
        return out;
    }

    /**
     * Splits a symbol's position path into round trips summing to its equity accounting (Σ trade.netReturn == Σ net): entry at the close before the first held bar,
     * exit at the last held bar's close, cost split between the ending and starting trade; {@code returnPct} is scaled per bar by {@code barWeight} (null = 1).
     */
    private List<TradeResult> extractTrades(
            double[] position,
            double[] close,
            Instant[] date,
            String symbol,
            double costRate,
            int warmupBars,
            double[] barWeight) {
        List<TradeResult> trades = new ArrayList<>();
        int length = position.length;
        int side = 0;
        int entryBar = -1;
        int barsHeld = 0;
        double gross = 0;
        double cost = 0;
        double exposure = 0;
        double weightedReturn = 0;
        for (int i = Math.max(0, warmupBars); i < length; i++) {
            double current = position[i];
            double previous = i > 0 ? position[i - 1] : 0;
            int direction = (int) Math.signum(current);
            double weight = barWeight == null ? 1.0 : barWeight[i];
            if (side != 0 && direction != side) {
                // the open trade ends: its last held bar is i-1, so close[i-1] is the exit fill
                double exitCost = Math.abs(previous) * costRate;
                cost += exitCost;
                weightedReturn -= weight * exitCost;
                trades.add(trade(
                        symbol,
                        side,
                        date,
                        close,
                        entryBar,
                        i - 1,
                        i,
                        barsHeld,
                        gross,
                        cost,
                        exposure,
                        weightedReturn,
                        false));
                side = 0;
            }
            if (direction == 0) {
                continue;
            }
            if (side == 0) {
                side = direction;
                entryBar = i;
                barsHeld = 0;
                gross = 0;
                exposure = Math.abs(current);
                // entry cost: the whole position, unless it was already held at the same sign
                double alreadyHeld = (int) Math.signum(previous) == direction ? previous : 0;
                cost = Math.abs(current - alreadyHeld) * costRate;
                weightedReturn = -weight * cost;
            } else {
                double resizeCost = Math.abs(current - previous) * costRate; // resized while held
                cost += resizeCost;
                weightedReturn -= weight * resizeCost;
            }
            double barGross = current * barReturn(close, i);
            gross += barGross;
            weightedReturn += weight * barGross;
            barsHeld++;
        }
        if (side != 0) {
            trades.add(trade(
                    symbol,
                    side,
                    date,
                    close,
                    entryBar,
                    length - 1,
                    length - 1,
                    barsHeld,
                    gross,
                    cost,
                    exposure,
                    weightedReturn,
                    true));
        }
        return trades;
    }

    private static TradeResult trade(
            String symbol,
            int side,
            Instant[] date,
            double[] close,
            int entryBar,
            int lastHeldBar,
            int exitDateBar,
            int barsHeld,
            double gross,
            double cost,
            double exposure,
            double returnPct,
            boolean open) {
        return new TradeResult(
                symbol,
                side > 0 ? "LONG" : "SHORT",
                date[entryBar],
                date[exitDateBar],
                close[Math.max(entryBar - 1, 0)],
                close[lastHeldBar],
                barsHeld,
                returnPct,
                gross,
                cost,
                exposure,
                open);
    }

    /**
     * Price-based round trips for the pairs strategy only (leg A, weight 0.5): a different model from {@link #extractTrades}, with a flat
     * round-trip cost; kept as it was, so pairs trades do not reconcile with the pairs equity.
     */
    private List<TradeResult> extractPairTrades(
            double[] position,
            double[] close,
            Instant[] date,
            String symbol,
            double costRate,
            int fromBar,
            double weight) {
        List<TradeResult> trades = new ArrayList<>();
        int currentSide = 0;
        int entryBar = -1;
        double entryPrice = 0;
        for (int i = Math.max(0, fromBar); i < position.length; i++) {
            int direction = (int) Math.signum(position[i]);
            if (direction != currentSide) {
                if (currentSide != 0) {
                    trades.add(pairTrade(
                            symbol,
                            currentSide,
                            date[entryBar],
                            date[i],
                            entryPrice,
                            close[i],
                            i - entryBar,
                            costRate,
                            weight,
                            Math.abs(position[entryBar]),
                            false));
                }
                if (direction != 0 && close[i] > 0) {
                    currentSide = direction;
                    entryBar = i;
                    entryPrice = close[i];
                } else {
                    currentSide = 0;
                    entryBar = -1;
                }
            }
        }
        if (currentSide != 0) {
            int lastBar = position.length - 1;
            trades.add(pairTrade(
                    symbol,
                    currentSide,
                    date[entryBar],
                    date[lastBar],
                    entryPrice,
                    close[lastBar],
                    lastBar - entryBar,
                    costRate,
                    weight,
                    Math.abs(position[entryBar]),
                    true));
        }
        return trades;
    }

    private static TradeResult pairTrade(
            String symbol,
            int side,
            Instant entryDate,
            Instant exitDate,
            double entryPrice,
            double exitPrice,
            int barsHeld,
            double costRate,
            double weight,
            double exposure,
            boolean open) {
        double gross = side * tradeReturn(entryPrice, exitPrice);
        double cost = 2 * costRate;
        return new TradeResult(
                symbol,
                side > 0 ? "LONG" : "SHORT",
                entryDate,
                exitDate,
                entryPrice,
                exitPrice,
                barsHeld,
                weight * (gross - cost),
                gross,
                cost,
                exposure,
                open);
    }

    public BacktestOutput runSingle(
            BarSeries bars, TradingStrategy strategy, Map<String, Double> params, BacktestConfig config) {
        Series series = computeSeries(bars, strategy, params, config);
        double[] equity = PerformanceMetrics.equityCurve(series.net, config.capital);
        double[] benchmarkEquity = PerformanceMetrics.equityCurve(series.bench, config.capital);
        double[] drawdown = PerformanceMetrics.drawdown(equity);
        Map<String, Double> metrics = PerformanceMetrics.compute(
                series.net, series.position, series.absPosition, series.bench, series.trades, config, series.dates);
        Map<String, Double> yearly = PerformanceMetrics.yearlyReturnsPct(series.net, series.dates);
        return new BacktestOutput(
                strategy.name(),
                List.of(bars.symbol),
                series.dates,
                equity,
                benchmarkEquity,
                drawdown,
                series.trades,
                metrics,
                Map.of(bars.symbol, metrics.get("totalReturnPct")),
                Map.of(bars.symbol, new SymbolResult(metrics, yearly)),
                yearly);
    }

    public BacktestOutput runPortfolio(
            List<BarSeries> data, TradingStrategy strategy, Map<String, Double> params, BacktestConfig config) {
        return runPortfolio(data, strategy, params, config, null, true);
    }

    public BacktestOutput runPortfolio(
            List<BarSeries> data,
            TradingStrategy strategy,
            Map<String, Double> params,
            BacktestConfig config,
            Executor executor) {
        return runPortfolio(data, strategy, params, config, executor, true);
    }

    /**
     * Runs the portfolio; a non-null {@code executor} computes each symbol's series in parallel (the blend and metrics stay single-threaded).
     * {@code symbolDetails} off skips per-symbol metrics for sweeps that only read the portfolio (the per-symbol maps are then empty).
     */
    public BacktestOutput runPortfolio(
            List<BarSeries> data,
            TradingStrategy strategy,
            Map<String, Double> params,
            BacktestConfig config,
            Executor executor,
            boolean symbolDetails) {
        return runPortfolio(data, strategy, params, config, executor, symbolDetails, null);
    }

    /**
     * @param onSymbolComputed called once per symbol as soon as its series is computed (possibly from a
     *                 pool thread, so it must be thread-safe); {@code null} for none. Must not throw.
     */
    public BacktestOutput runPortfolio(
            List<BarSeries> data,
            TradingStrategy strategy,
            Map<String, Double> params,
            BacktestConfig config,
            Executor executor,
            boolean symbolDetails,
            Consumer<String> onSymbolComputed) {
        List<Series> allSeries = new ArrayList<>();
        List<String> symbols = new ArrayList<>();
        TreeSet<Instant> allDates = new TreeSet<>();

        List<Series> computed;
        if (executor != null && data.size() > 1) {
            List<CompletableFuture<Series>> futures = data.stream()
                    .map(bars -> CompletableFuture.supplyAsync(
                            () -> computedAndReported(bars, strategy, params, config, onSymbolComputed), executor))
                    .toList();
            computed = futures.stream().map(CompletableFuture::join).toList();
        } else {
            computed = data.stream()
                    .map(bars -> computedAndReported(bars, strategy, params, config, onSymbolComputed))
                    .toList();
        }
        for (int i = 0; i < data.size(); i++) {
            Series series = computed.get(i);
            allSeries.add(series);
            symbols.add(data.get(i).symbol);
            allDates.addAll(Arrays.asList(series.dates));
        }
        computed = null; // release the immutable holder; per-symbol series live in `allSeries` now

        // Blend = equal-weight sleeves on a union date axis: a symbol is a 1/listedCount sleeve for as long as
        // it is listed (from its first to its last bar), where listedCount is the number of listed symbols.
        // Within that span a timestamp the symbol has no bar for contributes zero return and holds its
        // position (its price move over the gap is booked on its next bar, so nothing is lost) — it must
        // NOT drop out of the average, or the few symbols that do have a bar there would each get
        // extra weight and the total would exceed every symbol's own return (intraday data from thinly
        // traded names is full of such gaps). With every symbol listed throughout, the total is exactly
        // the mean of the symbols' totals. Scatter-add via one index map — no per-symbol
        // Map<Instant,Double> (that boxed millions of Doubles and OOM'd the box).
        Instant[] dates = allDates.toArray(new Instant[0]);
        int dateCount = dates.length;
        Map<Instant, Integer> dateIndex = new HashMap<>(dateCount * 2);
        for (int i = 0; i < dateCount; i++) {
            dateIndex.put(dates[i], i);
        }

        double[] net = new double[dateCount];
        double[] bench = new double[dateCount];
        double[] position = new double[dateCount];
        double[] absPosition = new double[dateCount];
        int[] listedCount = new int[dateCount];
        for (Series series : allSeries) {
            int barCount = series.dates.length;
            if (barCount == 0) {
                continue;
            }
            int firstDate = dateIndex.get(series.dates[0]);
            int lastDate = dateIndex.get(series.dates[barCount - 1]);
            int bar = 0;
            double heldPosition = 0;
            double heldAbsPosition = 0;
            for (int j = firstDate; j <= lastDate; j++) {
                while (bar < barCount && series.dates[bar].isBefore(dates[j])) {
                    bar++; // only if a series were not ascending
                }
                if (bar < barCount && series.dates[bar].equals(dates[j])) {
                    net[j] += series.net[bar];
                    bench[j] += series.bench[bar];
                    heldPosition = series.position[bar];
                    heldAbsPosition = series.absPosition[bar];
                    bar++;
                }
                position[j] += heldPosition;
                absPosition[j] += heldAbsPosition;
                listedCount[j]++;
            }
        }
        for (int i = 0; i < dateCount; i++) {
            if (listedCount[i] > 0) {
                net[i] /= listedCount[i];
                bench[i] /= listedCount[i];
                position[i] /= listedCount[i];
                absPosition[i] /= listedCount[i];
            }
        }

        // A symbol's weight is 1/listedCount at each of its bars, which is 1/N only while every symbol is
        // listed. Re-cut each symbol's trades with that per-bar weight so Σ returnPct reconciles
        // exactly with the blended total, however the histories are staggered.
        double costRate = (config.commissionBps + config.slippageBps) / 1e4;
        List<TradeResult> allTrades = new ArrayList<>();
        for (int k = 0; k < allSeries.size(); k++) {
            Series series = allSeries.get(k);
            double[] barWeight = new double[series.dates.length];
            for (int i = 0; i < barWeight.length; i++) {
                barWeight[i] = 1.0 / listedCount[dateIndex.get(series.dates[i])];
            }
            allTrades.addAll(extractTrades(
                    series.position,
                    series.close,
                    series.dates,
                    symbols.get(k),
                    costRate,
                    config.warmupBars,
                    barWeight));
        }

        // Each symbol's own standalone result, independent of the others — the blend just averages
        // these per-bar, so a lone symbol's numbers (same formulas as the portfolio's) are exact,
        // not an approximation. Its trades are the unweighted ones, so win rate / profit factor /
        // average win are per-trade figures for that symbol.
        Map<String, Double> symbolReturnsPct = new LinkedHashMap<>();
        Map<String, SymbolResult> symbolResults = new LinkedHashMap<>();
        for (int i = 0; symbolDetails && i < symbols.size(); i++) {
            Series series = allSeries.get(i);
            Map<String, Double> symbolMetrics = PerformanceMetrics.compute(
                    series.net, series.position, series.absPosition, series.bench, series.trades, config, series.dates);
            Map<String, Double> yearly = PerformanceMetrics.yearlyReturnsPct(series.net, series.dates);
            symbolResults.put(symbols.get(i), new SymbolResult(symbolMetrics, yearly));
            symbolReturnsPct.put(symbols.get(i), symbolMetrics.get("totalReturnPct"));
        }
        allSeries.clear(); // per-bar arrays no longer needed — free them before metrics

        double[] equity = PerformanceMetrics.equityCurve(net, config.capital);
        double[] benchmarkEquity = PerformanceMetrics.equityCurve(bench, config.capital);
        double[] drawdown = PerformanceMetrics.drawdown(equity);
        Map<String, Double> metrics =
                PerformanceMetrics.compute(net, position, absPosition, bench, allTrades, config, dates);
        return new BacktestOutput(
                strategy.name(),
                symbols,
                dates,
                equity,
                benchmarkEquity,
                drawdown,
                allTrades,
                metrics,
                symbolReturnsPct,
                symbolResults,
                PerformanceMetrics.yearlyReturnsPct(net, dates));
    }

    public BacktestOutput runPairs(BarSeries legA, BarSeries legB, PairsStrategy strategy, BacktestConfig config) {
        // align on common dates
        Map<Instant, Integer> legBIndex = new HashMap<>();
        for (int i = 0; i < legB.date.length; i++) {
            legBIndex.put(legB.date[i], i);
        }
        List<Instant> commonDates = new ArrayList<>();
        List<Double> commonCloseA = new ArrayList<>();
        List<Double> commonCloseB = new ArrayList<>();
        for (int i = 0; i < legA.date.length; i++) {
            Integer legBBar = legBIndex.get(legA.date[i]);
            if (legBBar != null) {
                commonDates.add(legA.date[i]);
                commonCloseA.add(legA.close[i]);
                commonCloseB.add(legB.close[legBBar]);
            }
        }
        int length = commonDates.size();
        double[] closeA = new double[length];
        double[] closeB = new double[length];
        for (int i = 0; i < length; i++) {
            closeA[i] = commonCloseA.get(i);
            closeB[i] = commonCloseB.get(i);
        }
        double[][] signals = strategy.signals(closeA, closeB);
        double[] positionA = new double[length];
        double[] positionB = new double[length];
        for (int i = 0; i < length; i++) {
            int signalBar = i - config.execLag;
            positionA[i] = signalBar >= 0 ? signals[0][signalBar] * config.positionSize : 0;
            positionB[i] = signalBar >= 0 ? signals[1][signalBar] * config.positionSize : 0;
        }
        double[] net = new double[length];
        double[] bench = new double[length];
        double[] absPosition = new double[length];
        double costRate = (config.commissionBps + config.slippageBps) / 1e4;
        for (int i = 0; i < length; i++) {
            double returnA = barReturn(closeA, i);
            double returnB = barReturn(closeB, i);
            double previousA = i > 0 ? positionA[i - 1] : 0;
            double previousB = i > 0 ? positionB[i - 1] : 0;
            double turnover = Math.abs(positionA[i] - previousA) + Math.abs(positionB[i] - previousB);
            net[i] = 0.5 * positionA[i] * returnA + 0.5 * positionB[i] * returnB - turnover * costRate;
            bench[i] = 0.5 * returnA + 0.5 * returnB;
            absPosition[i] = Math.abs(positionA[i]);
            if (i < config.warmupBars) {
                net[i] = 0;
                absPosition[i] = 0;
            }
        }
        Instant[] dates = commonDates.toArray(new Instant[0]);
        // net = 0.5*legA + 0.5*legB, and trades are extracted from leg A only, so weight the A-leg trades by 0.5
        List<TradeResult> trades = extractPairTrades(
                positionA, closeA, dates, legA.symbol + "/" + legB.symbol, costRate, config.warmupBars, 0.5);
        double[] equity = PerformanceMetrics.equityCurve(net, config.capital);
        double[] benchmarkEquity = PerformanceMetrics.equityCurve(bench, config.capital);
        double[] drawdown = PerformanceMetrics.drawdown(equity);
        Map<String, Double> metrics =
                PerformanceMetrics.compute(net, positionA, absPosition, bench, trades, config, dates);
        if (tracePerSymbol) {
            log.info(
                    "Backtest: pairs_trading × {}/{} — {} common bars, {} trades",
                    legA.symbol,
                    legB.symbol,
                    length,
                    trades.size());
        }
        return new BacktestOutput(
                "pairs_trading",
                List.of(legA.symbol, legB.symbol),
                dates,
                equity,
                benchmarkEquity,
                drawdown,
                trades,
                metrics,
                Map.of(),
                Map.of(),
                PerformanceMetrics.yearlyReturnsPct(net, dates));
    }

    /** Point-to-point trade return, guarded against a bad entry/exit price. */
    private static double tradeReturn(double entryPrice, double exitPrice) {
        return (entryPrice > 0 && exitPrice > 0) ? exitPrice / entryPrice - 1 : 0;
    }
}
