package com.quant.finance.execution.service;

import com.quant.finance.execution.dto.Dtos.*;
import com.quant.finance.execution.engine.BacktestConfig;
import com.quant.finance.execution.engine.Timeframe;
import com.quant.finance.execution.strategy.BarSeries;
import com.quant.finance.execution.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Brute-force parameter sweep for a single strategy: run the portfolio backtest across a
 * 1-D or 2-D grid of one/two parameters and rank the cells by a chosen metric. Bars are
 * loaded once and reused for every cell.
 */
@Service
public class OptimizerService {

    private static final int MAX_CELLS = 400;
    private static final Logger log = LoggerFactory.getLogger(OptimizerService.class);

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
