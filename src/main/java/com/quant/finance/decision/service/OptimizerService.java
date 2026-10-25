package com.quant.finance.decision.service;

import com.quant.finance.decision.dto.Dtos.OptimizeCellDto;
import com.quant.finance.decision.dto.Dtos.OptimizeRequest;
import com.quant.finance.decision.dto.Dtos.OptimizeResultDto;
import com.quant.finance.decision.dto.Dtos.RiskCellDto;
import com.quant.finance.decision.dto.Dtos.RiskOptimizeBulkEntryDto;
import com.quant.finance.decision.dto.Dtos.RiskOptimizeBulkResultDto;
import com.quant.finance.decision.dto.Dtos.RiskOptimizeResultDto;
import com.quant.finance.decision.engine.BacktestConfig;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Brute-force parameter sweep for one strategy: runs the portfolio backtest over a 1-D or 2-D parameter grid and ranks the cells by a metric,
 * with the bars loaded once and reused for every cell.
 */
@Service
@Slf4j
public class OptimizerService {

    private static final int MAX_CELLS = 400;
    private final BacktestService backtests;
    private final StrategyService strategies;

    public OptimizerService(BacktestService backtests, StrategyService strategies) {
        this.backtests = backtests;
        this.strategies = strategies;
    }

    public OptimizeResultDto optimize(String name, OptimizeRequest request) {
        TradingStrategy strategy = strategies.getStrategy(name); // honours invert / direction override
        Map<String, Double> defaults = strategies.defaultParams(name);
        String metric = (request.metric() == null || request.metric().isBlank())
                ? "sharpe"
                : request.metric().trim();

        require(request.param1(), defaults, "param1");
        List<Double> axis1 = axis(request.param1(), request.from1(), request.to1(), request.step1());
        boolean twoD = request.param2() != null && !request.param2().isBlank();
        List<Double> axis2;
        if (twoD) {
            require(request.param2(), defaults, "param2");
            axis2 = axis(request.param2(), request.from2(), request.to2(), request.step2());
        } else {
            axis2 = Collections.singletonList(Double.NaN);
        }
        if ((long) axis1.size() * axis2.size() > MAX_CELLS) {
            throw new IllegalArgumentException("Grid too large (" + axis1.size() + "x" + axis2.size() + "); max "
                    + MAX_CELLS + " cells — widen the step or narrow the range.");
        }

        Timeframe frame = Timeframe.resolve(request.timeframe(), strategies.recommendedTimeframe(name));
        BacktestConfig config = BacktestConfig.builder()
                .capital(request.capital() != null ? request.capital() : 100_000)
                .commissionBps(request.commissionBps() != null ? request.commissionBps() : 1.0)
                .slippageBps(request.slippageBps() != null ? request.slippageBps() : 2.0)
                .allowShort(request.allowShort() == null || request.allowShort())
                .timeframe(frame)
                .build();

        List<String> symbols = backtests.universeOr(request.symbols());
        List<BarSeries> data = backtests.loadData(symbols, request.start(), request.end(), config.timeframe);

        int cells = axis1.size() * axis2.size();
        long startMillis = System.currentTimeMillis();
        log.info(
                "Optimize: '{}' sweeping {}{} = {} cells on {} symbol(s) @ {}, score by {}",
                name,
                request.param1(),
                twoD ? " x " + request.param2() : "",
                cells,
                symbols.size(),
                config.timeframe,
                metric);

        // one task per grid cell, fanned out across the shared backtest pool
        List<CompletableFuture<OptimizeCellDto>> futures = new ArrayList<>(cells);
        for (double value1 : axis1) {
            for (double value2 : axis2) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> {
                            Map<String, Double> params = new LinkedHashMap<>(defaults);
                            params.put(request.param1(), value1);
                            if (twoD) {
                                params.put(request.param2(), value2);
                            }
                            Map<String, Double> metrics = backtests.evaluate(strategy, params, data, config);
                            double score = metrics.getOrDefault(metric, Double.NaN);
                            Map<String, Double> tried = new LinkedHashMap<>();
                            tried.put(request.param1(), value1);
                            if (twoD) {
                                tried.put(request.param2(), value2);
                            }
                            return new OptimizeCellDto(
                                    tried, metrics, Double.isNaN(score) ? Double.NEGATIVE_INFINITY : score);
                        },
                        backtests.executor()));
            }
        }
        List<OptimizeCellDto> grid = futures.stream()
                .map(CompletableFuture::join)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        grid.sort(Comparator.comparingDouble(OptimizeCellDto::score).reversed());
        OptimizeCellDto best = grid.isEmpty() ? null : grid.get(0);
        Map<String, Double> bestParams = new LinkedHashMap<>(defaults);
        if (best != null) {
            bestParams.putAll(best.params());
        }
        log.info(
                "Optimize: '{}' done in {} ms — best {} {}={}",
                name,
                System.currentTimeMillis() - startMillis,
                best != null ? best.params() : "{}",
                metric,
                best != null ? best.score() : Double.NaN);
        return new OptimizeResultDto(name, metric, defaults, bestParams, best, grid);
    }

    // ---- risk-default sweep: timeframe x stop-loss% x take-profit%, scored by total return ----

    private static final List<Timeframe> RISK_TIMEFRAMES = List.of(Timeframe.M15, Timeframe.H1, Timeframe.D1);
    private static final double[] SL_GRID = {0, 1, 2, 3, 5};
    private static final double[] TP_GRID = {0, 2, 5, 10, 15};
    /** Leading share of each series used to pick a combo; the rest is an unseen holdout it's
     *  then scored on — so the reported result isn't graded on the data it was chosen from. */
    private static final double TRAIN_FRACTION = 0.7;

    private record SplitData(Map<Timeframe, List<BarSeries>> train, Map<Timeframe, List<BarSeries>> test) {}

    private SplitData loadSplit(List<String> symbolList, LocalDate start, LocalDate end) {
        Map<Timeframe, List<BarSeries>> train = new LinkedHashMap<>();
        Map<Timeframe, List<BarSeries>> test = new LinkedHashMap<>();
        for (Timeframe frame : RISK_TIMEFRAMES) {
            List<BarSeries> data = backtests.loadData(symbolList, start, end, frame);
            List<BarSeries> trainList = new ArrayList<>();
            List<BarSeries> testList = new ArrayList<>();
            for (BarSeries bars : data) {
                BarSeries[] split = bars.trainTestSplit(TRAIN_FRACTION);
                trainList.add(split[0]);
                testList.add(split[1]);
            }
            train.put(frame, trainList);
            test.put(frame, testList);
        }
        return new SplitData(train, test);
    }

    /** Finds the best (timeframe, stopLoss%, takeProfit%) combo for one strategy, without saving it. */
    public RiskOptimizeResultDto optimizeRiskDefaults(
            String name,
            List<String> symbols,
            LocalDate start,
            LocalDate end,
            Double capital,
            Double commissionBps,
            Double slippageBps,
            Boolean allowShort) {
        List<String> resolvedSymbols = backtests.universeOr(symbols);
        SplitData split = loadSplit(resolvedSymbols, start, end);
        return sweep(
                name,
                split,
                capital != null ? capital : 100_000,
                commissionBps != null ? commissionBps : 1.0,
                slippageBps != null ? slippageBps : 2.0,
                allowShort == null || allowShort);
    }

    /**
     * Runs {@link #optimizeRiskDefaults} for every enabled, non-archived strategy and persists each winner via {@link StrategyService#saveRiskDefaults};
     * bars are loaded once per timeframe and shared across all strategies (3 loads, not one set per strategy).
     */
    public RiskOptimizeBulkResultDto optimizeRiskDefaultsBulk(
            List<String> symbols,
            LocalDate start,
            LocalDate end,
            Double capital,
            Double commissionBps,
            Double slippageBps,
            Boolean allowShort) {
        List<String> names = new ArrayList<>(strategies.getEnabledStrategies().keySet());
        List<String> resolvedSymbols = backtests.universeOr(symbols);
        SplitData split = loadSplit(resolvedSymbols, start, end);

        double capitalOrDefault = capital != null ? capital : 100_000;
        double commissionOrDefault = commissionBps != null ? commissionBps : 1.0;
        double slippageOrDefault = slippageBps != null ? slippageBps : 2.0;
        boolean allowShortOrDefault = allowShort == null || allowShort;

        long startMillis = System.currentTimeMillis();
        List<RiskOptimizeBulkEntryDto> results = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        for (String name : names) {
            try {
                RiskOptimizeResultDto result = sweep(
                        name, split, capitalOrDefault, commissionOrDefault, slippageOrDefault, allowShortOrDefault);
                strategies.saveRiskDefaults(
                        name,
                        result.best().timeframe(),
                        result.best().stopLossPct(),
                        result.best().takeProfitPct());
                results.add(new RiskOptimizeBulkEntryDto(name, result.best(), result.outOfSample(), true, null));
                succeeded++;
                log.info(
                        "Risk-default optimize: '{}' train {}={} vs out-of-sample {}={}",
                        name,
                        result.metric(),
                        result.best().score(),
                        result.metric(),
                        result.outOfSample().score());
            } catch (RuntimeException e) {
                log.warn(
                        "Risk-default optimize: '{}' failed — {}: {}",
                        name,
                        e.getClass().getSimpleName(),
                        e.getMessage());
                results.add(new RiskOptimizeBulkEntryDto(name, null, null, false, e.getMessage()));
                failed++;
            }
        }
        log.info(
                "Risk-default optimize: {} strategies — {} ok, {} failed, in {} ms",
                names.size(),
                succeeded,
                failed,
                System.currentTimeMillis() - startMillis);
        return new RiskOptimizeBulkResultDto(names.size(), succeeded, failed, results);
    }

    private RiskOptimizeResultDto sweep(
            String name,
            SplitData split,
            double capital,
            double commissionBps,
            double slippageBps,
            boolean allowShort) {
        TradingStrategy strategy = strategies.getStrategy(name);
        Map<String, Double> params = strategies.getParams(name);

        List<CompletableFuture<RiskCellDto>> futures = new ArrayList<>();
        for (Timeframe frame : RISK_TIMEFRAMES) {
            List<BarSeries> trainData = split.train().get(frame);
            BacktestConfig baseConfig = BacktestConfig.builder()
                    .capital(capital)
                    .commissionBps(commissionBps)
                    .slippageBps(slippageBps)
                    .allowShort(allowShort)
                    .timeframe(frame)
                    .build();
            for (double stopLossPct : SL_GRID) {
                for (double takeProfitPct : TP_GRID) {
                    futures.add(CompletableFuture.supplyAsync(
                            () -> {
                                Map<String, Double> metrics = backtests.evaluate(
                                        strategy, params, trainData, baseConfig.withRisk(stopLossPct, takeProfitPct));
                                double score = metrics.getOrDefault("totalReturnPct", Double.NEGATIVE_INFINITY);
                                return new RiskCellDto(frame.name(), stopLossPct, takeProfitPct, metrics, score);
                            },
                            backtests.executor()));
                }
            }
        }
        RiskCellDto best = null;
        for (CompletableFuture<RiskCellDto> future : futures) {
            RiskCellDto cell = future.join();
            if (best == null || cell.score() > best.score()) {
                best = cell;
            }
        }

        // score that exact winning combo on the holdout slice it never influenced
        Timeframe bestFrame = Timeframe.valueOf(best.timeframe());
        List<BarSeries> testData = split.test().get(bestFrame);
        BacktestConfig testConfig = BacktestConfig.builder()
                .capital(capital)
                .commissionBps(commissionBps)
                .slippageBps(slippageBps)
                .allowShort(allowShort)
                .timeframe(bestFrame)
                .build()
                .withRisk(best.stopLossPct(), best.takeProfitPct());
        Map<String, Double> testMetrics = backtests.evaluate(strategy, params, testData, testConfig);
        RiskCellDto outOfSample = new RiskCellDto(
                best.timeframe(),
                best.stopLossPct(),
                best.takeProfitPct(),
                testMetrics,
                testMetrics.getOrDefault("totalReturnPct", Double.NEGATIVE_INFINITY));

        return new RiskOptimizeResultDto(name, "totalReturnPct", best, outOfSample, TRAIN_FRACTION, futures.size());
    }

    private static void require(String param, Map<String, Double> defaults, String which) {
        if (param == null || param.isBlank()) {
            throw new IllegalArgumentException(which + " is required");
        }
        if (!defaults.containsKey(param)) {
            throw new IllegalArgumentException("Unknown " + which + " '" + param + "'; valid: " + defaults.keySet());
        }
    }

    private static List<Double> axis(String param, Double from, Double to, Double step) {
        if (from == null || to == null || step == null || step == 0) {
            throw new IllegalArgumentException("from/to/step required for '" + param + "'");
        }
        double low = Math.min(from, to);
        double high = Math.max(from, to);
        double stepSize = Math.abs(step);
        List<Double> out = new ArrayList<>();
        for (double value = low; value <= high + 1e-9; value += stepSize) {
            out.add(Math.round(value * 1e6) / 1e6);
        }
        if (out.isEmpty()) {
            out.add(low);
        }
        return out;
    }
}
