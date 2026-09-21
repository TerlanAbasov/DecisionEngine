package com.quant.finance.decision.service;

import com.quant.finance.decision.dto.Dtos.*;
import com.quant.finance.decision.engine.BacktestConfig;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;

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

    public OptimizeResultDto optimize(String name, OptimizeRequest req) {
        TradingStrategy strat = strategies.getStrategy(name);          // honours invert / direction override
        Map<String, Double> defaults = strategies.defaultParams(name);
        String metric = (req.metric() == null || req.metric().isBlank()) ? "sharpe" : req.metric().trim();

        require(req.param1(), defaults, "param1");
        List<Double> axis1 = axis(req.param1(), req.from1(), req.to1(), req.step1());
        boolean twoD = req.param2() != null && !req.param2().isBlank();
        List<Double> axis2;
        if (twoD) {
            require(req.param2(), defaults, "param2");
            axis2 = axis(req.param2(), req.from2(), req.to2(), req.step2());
        } else {
            axis2 = Collections.singletonList(Double.NaN);
        }
        if ((long) axis1.size() * axis2.size() > MAX_CELLS)
            throw new IllegalArgumentException("Grid too large (" + axis1.size() + "x" + axis2.size()
                    + "); max " + MAX_CELLS + " cells — widen the step or narrow the range.");

        Timeframe tf = Timeframe.resolve(req.timeframe(), strategies.recommendedTimeframe(name));
        BacktestConfig cfg = BacktestConfig.builder()
                .capital(req.capital() != null ? req.capital() : 100_000)
                .commissionBps(req.commissionBps() != null ? req.commissionBps() : 1.0)
                .slippageBps(req.slippageBps() != null ? req.slippageBps() : 2.0)
                .allowShort(req.allowShort() == null || req.allowShort())
                .timeframe(tf)
                .build();

        List<String> symbols = backtests.universeOr(req.symbols());
        List<BarSeries> data = backtests.loadData(symbols, req.start(), req.end(), cfg.timeframe);

        int cells = axis1.size() * axis2.size();
        long t0 = System.currentTimeMillis();
        log.info("Optimize: '{}' sweeping {}{} = {} cells on {} symbol(s) @ {}, score by {}",
                name, req.param1(), twoD ? " x " + req.param2() : "", cells, symbols.size(), cfg.timeframe, metric);

        // one task per grid cell, fanned out across the shared backtest pool
        List<CompletableFuture<OptimizeCellDto>> futures = new ArrayList<>(cells);
        for (double v1 : axis1) {
            for (double v2 : axis2) {
                final double a = v1, b = v2;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    Map<String, Double> params = new LinkedHashMap<>(defaults);
                    params.put(req.param1(), a);
                    if (twoD) params.put(req.param2(), b);
                    Map<String, Double> m = backtests.evaluate(strat, params, data, cfg);
                    double score = m.getOrDefault(metric, Double.NaN);
                    Map<String, Double> tried = new LinkedHashMap<>();
                    tried.put(req.param1(), a);
                    if (twoD) tried.put(req.param2(), b);
                    return new OptimizeCellDto(tried, m, Double.isNaN(score) ? Double.NEGATIVE_INFINITY : score);
                }, backtests.executor()));
            }
        }
        List<OptimizeCellDto> grid = futures.stream().map(CompletableFuture::join)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        grid.sort(Comparator.comparingDouble(OptimizeCellDto::score).reversed());
        OptimizeCellDto best = grid.isEmpty() ? null : grid.get(0);
        Map<String, Double> bestParams = new LinkedHashMap<>(defaults);
        if (best != null) bestParams.putAll(best.params());
        log.info("Optimize: '{}' done in {} ms — best {} {}={}", name, System.currentTimeMillis() - t0,
                best != null ? best.params() : "{}", metric, best != null ? best.score() : Double.NaN);
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

    private SplitData loadSplit(List<String> syms, LocalDate start, LocalDate end) {
        Map<Timeframe, List<BarSeries>> train = new LinkedHashMap<>(), test = new LinkedHashMap<>();
        for (Timeframe tf : RISK_TIMEFRAMES) {
            List<BarSeries> data = backtests.loadData(syms, start, end, tf);
            List<BarSeries> trainList = new ArrayList<>(), testList = new ArrayList<>();
            for (BarSeries b : data) {
                BarSeries[] split = b.trainTestSplit(TRAIN_FRACTION);
                trainList.add(split[0]);
                testList.add(split[1]);
            }
            train.put(tf, trainList);
            test.put(tf, testList);
        }
        return new SplitData(train, test);
    }

    /** Finds the best (timeframe, stopLoss%, takeProfit%) combo for one strategy, without saving it. */
    public RiskOptimizeResultDto optimizeRiskDefaults(String name, List<String> symbols, LocalDate start,
                                                      LocalDate end, Double capital, Double commissionBps,
                                                      Double slippageBps, Boolean allowShort) {
        List<String> syms = backtests.universeOr(symbols);
        SplitData split = loadSplit(syms, start, end);
        return sweep(name, split,
                capital != null ? capital : 100_000, commissionBps != null ? commissionBps : 1.0,
                slippageBps != null ? slippageBps : 2.0, allowShort == null || allowShort);
    }

    /**
     * Runs {@link #optimizeRiskDefaults} for every enabled, non-archived strategy and persists each winner via {@link StrategyService#saveRiskDefaults};
     * bars are loaded once per timeframe and shared across all strategies (3 loads, not one set per strategy).
     */
    public RiskOptimizeBulkResultDto optimizeRiskDefaultsBulk(List<String> symbols, LocalDate start, LocalDate end,
                                                              Double capital, Double commissionBps,
                                                              Double slippageBps, Boolean allowShort) {
        List<String> names = new ArrayList<>(strategies.getEnabledStrategies().keySet());
        List<String> syms = backtests.universeOr(symbols);
        SplitData split = loadSplit(syms, start, end);

        double cap = capital != null ? capital : 100_000;
        double comm = commissionBps != null ? commissionBps : 1.0;
        double slip = slippageBps != null ? slippageBps : 2.0;
        boolean allowShortEff = allowShort == null || allowShort;

        long t0 = System.currentTimeMillis();
        List<RiskOptimizeBulkEntryDto> results = new ArrayList<>();
        int ok = 0, failed = 0;
        for (String name : names) {
            try {
                RiskOptimizeResultDto r = sweep(name, split, cap, comm, slip, allowShortEff);
                strategies.saveRiskDefaults(name, r.best().timeframe(), r.best().stopLossPct(), r.best().takeProfitPct());
                results.add(new RiskOptimizeBulkEntryDto(name, r.best(), r.outOfSample(), true, null));
                ok++;
                log.info("Risk-default optimize: '{}' train {}={} vs out-of-sample {}={}", name, r.metric(),
                        r.best().score(), r.metric(), r.outOfSample().score());
            } catch (RuntimeException e) {
                log.warn("Risk-default optimize: '{}' failed — {}: {}", name, e.getClass().getSimpleName(), e.getMessage());
                results.add(new RiskOptimizeBulkEntryDto(name, null, null, false, e.getMessage()));
                failed++;
            }
        }
        log.info("Risk-default optimize: {} strategies — {} ok, {} failed, in {} ms",
                names.size(), ok, failed, System.currentTimeMillis() - t0);
        return new RiskOptimizeBulkResultDto(names.size(), ok, failed, results);
    }

    private RiskOptimizeResultDto sweep(String name, SplitData split,
                                        double capital, double commissionBps, double slippageBps, boolean allowShort) {
        TradingStrategy strat = strategies.getStrategy(name);
        Map<String, Double> params = strategies.getParams(name);

        List<CompletableFuture<RiskCellDto>> futures = new ArrayList<>();
        for (Timeframe tf : RISK_TIMEFRAMES) {
            List<BarSeries> trainData = split.train().get(tf);
            BacktestConfig baseCfg = BacktestConfig.builder()
                    .capital(capital).commissionBps(commissionBps).slippageBps(slippageBps)
                    .allowShort(allowShort).timeframe(tf).build();
            for (double sl : SL_GRID) {
                for (double tp : TP_GRID) {
                    final double slF = sl, tpF = tp;
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        Map<String, Double> m = backtests.evaluate(strat, params, trainData, baseCfg.withRisk(slF, tpF));
                        double score = m.getOrDefault("totalReturnPct", Double.NEGATIVE_INFINITY);
                        return new RiskCellDto(tf.name(), slF, tpF, m, score);
                    }, backtests.executor()));
                }
            }
        }
        RiskCellDto best = null;
        for (CompletableFuture<RiskCellDto> f : futures) {
            RiskCellDto cell = f.join();
            if (best == null || cell.score() > best.score()) best = cell;
        }

        // score that exact winning combo on the holdout slice it never influenced
        Timeframe bestTf = Timeframe.valueOf(best.timeframe());
        List<BarSeries> testData = split.test().get(bestTf);
        BacktestConfig testCfg = BacktestConfig.builder()
                .capital(capital).commissionBps(commissionBps).slippageBps(slippageBps)
                .allowShort(allowShort).timeframe(bestTf)
                .build().withRisk(best.stopLossPct(), best.takeProfitPct());
        Map<String, Double> testMetrics = backtests.evaluate(strat, params, testData, testCfg);
        RiskCellDto outOfSample = new RiskCellDto(best.timeframe(), best.stopLossPct(), best.takeProfitPct(),
                testMetrics, testMetrics.getOrDefault("totalReturnPct", Double.NEGATIVE_INFINITY));

        return new RiskOptimizeResultDto(name, "totalReturnPct", best, outOfSample, TRAIN_FRACTION, futures.size());
    }

    private static void require(String param, Map<String, Double> defaults, String which) {
        if (param == null || param.isBlank())
            throw new IllegalArgumentException(which + " is required");
        if (!defaults.containsKey(param))
            throw new IllegalArgumentException("Unknown " + which + " '" + param + "'; valid: " + defaults.keySet());
    }

    private static List<Double> axis(String param, Double from, Double to, Double step) {
        if (from == null || to == null || step == null || step == 0)
            throw new IllegalArgumentException("from/to/step required for '" + param + "'");
        double lo = Math.min(from, to), hi = Math.max(from, to), s = Math.abs(step);
        List<Double> out = new ArrayList<>();
        for (double v = lo; v <= hi + 1e-9; v += s) out.add(Math.round(v * 1e6) / 1e6);
        if (out.isEmpty()) out.add(lo);
        return out;
    }
}
