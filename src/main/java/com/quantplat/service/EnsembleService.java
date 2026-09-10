package com.quantplat.service;

import com.quantplat.dto.Dtos.*;
import com.quantplat.engine.BacktestConfig;
import com.quantplat.engine.BacktestOutput;
import com.quantplat.engine.BarResampler;
import com.quantplat.engine.PerformanceMetrics;
import com.quantplat.engine.Timeframe;
import com.quantplat.strategy.BarSeries;
import com.quantplat.strategy.TradingStrategy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Blends several strategies into one daily-rebalanced portfolio: each leg is backtested
 * on the same universe, its equity curve turned into a daily-return stream, and the
 * streams combined by weight ({@code config} / {@code equal} / {@code sharpe}). The
 * result is a single equity curve plus each leg's standalone metrics.
 */
@Service
public class EnsembleService {

    private static final int MAX_LEGS = 40;
    private static final double CAPITAL_DEFAULT = 100_000;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EnsembleService.class);

    private final BacktestService backtests;
    private final StrategyService strategies;

    public EnsembleService(BacktestService backtests, StrategyService strategies) {
        this.backtests = backtests;
        this.strategies = strategies;
    }

    public EnsembleResultDto ensemble(EnsembleRequest req) {
        List<String> names = (req.strategyNames() == null || req.strategyNames().isEmpty())
                ? new ArrayList<>(strategies.getEnabledStrategies().keySet())
                : req.strategyNames();
        if (names.isEmpty()) throw new IllegalStateException("No strategies selected and none enabled");
        if (names.size() > MAX_LEGS)
            throw new IllegalArgumentException("Too many legs (" + names.size() + "); max " + MAX_LEGS);

        String weighting = (req.weighting() == null || req.weighting().isBlank()) ? "config" : req.weighting().trim();
        double capital = req.capital() != null ? req.capital() : CAPITAL_DEFAULT;
        boolean perStrategyTf = Boolean.TRUE.equals(req.perStrategyTimeframe());

        BacktestConfig cfg = BacktestConfig.builder()
                .capital(capital)
                .commissionBps(req.commissionBps() != null ? req.commissionBps() : 1.0)
                .slippageBps(req.slippageBps() != null ? req.slippageBps() : 2.0)
                .allowShort(req.allowShort() == null || req.allowShort())
                .positionSize(req.positionSize() != null ? req.positionSize() : 1.0)
                .timeframe(perStrategyTf ? Timeframe.NATIVE : Timeframe.from(req.timeframe()))
                .build();

        List<String> symbols = backtests.universeOr(req.symbols());
        // per-strategy frame: load native once and resample per leg; otherwise resample once here
        List<BarSeries> data = backtests.loadData(symbols, req.start(), req.end(),
                perStrategyTf ? Timeframe.NATIVE : cfg.timeframe);

        // --- run each leg, collect date-keyed return maps ---
        List<String> legNames = new ArrayList<>();
        List<Map<Instant, Double>> legRet = new ArrayList<>();
        List<Map<Instant, Double>> legBench = new ArrayList<>();
        List<Map<String, Double>> legMetrics = new ArrayList<>();
        TreeSet<Instant> allDates = new TreeSet<>();
        long batchStart = System.currentTimeMillis();
        log.info("Ensemble: {} legs on {} symbol(s), {}-weighted ({})", names.size(), symbols.size(),
                weighting, perStrategyTf ? "per-strategy timeframe" : cfg.timeframe.toString());
        // prep every leg single-threaded (strategy / params / timeframe / data — the DB touches),
        // then run the CPU-bound backtests in parallel on the shared pool
        record Leg(String name, TradingStrategy strat, java.util.Map<String, Double> params,
                   BacktestConfig cfg, List<BarSeries> data) {}
        java.util.Map<Timeframe, List<BarSeries>> tfData = new java.util.HashMap<>();
        List<Leg> legJobs = new ArrayList<>();
        for (String name : names) {
            List<BarSeries> legData = data;
            BacktestConfig legCfg = cfg;
            if (perStrategyTf) {
                Timeframe tf = strategies.recommendedTimeframe(name);
                legCfg = cfg.withTimeframe(tf);
                legData = tfData.computeIfAbsent(tf,
                        t -> data.stream().map(b -> BarResampler.resample(b, t)).toList());
            }
            legJobs.add(new Leg(name, strategies.getStrategy(name), strategies.getParams(name), legCfg, legData));
        }
        List<java.util.concurrent.CompletableFuture<BacktestOutput>> legFutures = new ArrayList<>();
        for (Leg leg : legJobs) {
            legFutures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                long t0 = System.currentTimeMillis();
                BacktestOutput o = backtests.runOnce(leg.strat(), leg.params(), leg.data(), leg.cfg());
                log.info("Ensemble leg '{}' @ {} computed in {} ms", leg.name(), leg.cfg().timeframe,
                        System.currentTimeMillis() - t0);
                return o;
            }, backtests.executor()));
        }
        for (int k = 0; k < legJobs.size(); k++) {
            BacktestOutput o = legFutures.get(k).join();
            legNames.add(legJobs.get(k).name());
            legRet.add(toReturns(o.dates, o.equity, capital));
            legBench.add(toReturns(o.dates, o.benchmark, capital));
            legMetrics.add(o.metrics);
            allDates.addAll(Arrays.asList(o.dates));
        }
        if (allDates.isEmpty()) throw new IllegalStateException("Ensemble produced no dated results");

        double[] weights = weights(weighting, legNames, legMetrics);

        Instant[] dates = allDates.toArray(new Instant[0]);
        double[] net = new double[dates.length];
        double[] benchNet = new double[dates.length];
        for (int i = 0; i < dates.length; i++) {
            Instant d = dates[i];
            double wsum = 0, acc = 0, bacc = 0, bw = 0;
            for (int k = 0; k < legNames.size(); k++) {
                Double r = legRet.get(k).get(d);
                if (r != null) { acc += weights[k] * r; wsum += weights[k]; }
                Double br = legBench.get(k).get(d);
                if (br != null) { bacc += br; bw += 1; }
            }
            net[i] = wsum > 0 ? acc / wsum : 0;
            benchNet[i] = bw > 0 ? bacc / bw : 0;
        }

        double[] equity = PerformanceMetrics.equityCurve(net, capital);
        double[] benchEq = PerformanceMetrics.equityCurve(benchNet, capital);
        double[] dd = PerformanceMetrics.drawdown(equity);
        Map<String, Double> metrics = PerformanceMetrics.returnMetrics(net, capital, 0, dates);

        double wtotal = 0;
        for (double w : weights) wtotal += w;
        List<EnsembleLegDto> legs = new ArrayList<>();
        for (int k = 0; k < legNames.size(); k++)
            legs.add(new EnsembleLegDto(legNames.get(k),
                    wtotal > 0 ? Math.round(weights[k] / wtotal * 1e4) / 1e4 : 0, legMetrics.get(k)));
        legs.sort((a, b) -> Double.compare(b.weight(), a.weight()));

        List<String> dateStrs = new ArrayList<>(dates.length);
        for (Instant d : dates) dateStrs.add(d.toString());
        String tfLabel = perStrategyTf ? "PER_STRATEGY" : cfg.timeframe.name();
        log.info("Ensemble: {} legs done in {} ms — blended return={}% sharpe={}", names.size(),
                System.currentTimeMillis() - batchStart,
                metrics.getOrDefault("totalReturnPct", 0.0), metrics.getOrDefault("sharpe", 0.0));
        return new EnsembleResultDto(symbols, dates[0], dates[dates.length - 1], tfLabel,
                dates.length, weighting, metrics, dateStrs, equity, benchEq, dd, legs);
    }

    /**
     * Per-bar net return of a leg, recovered from its (additive, fixed-notional) equity path:
     * {@code (equity[i] - equity[i-1]) / capital}.
     */
    private static Map<Instant, Double> toReturns(Instant[] dates, double[] equity, double capital) {
        Map<Instant, Double> out = new HashMap<>();
        double prev = capital;
        for (int i = 0; i < dates.length; i++) {
            double e = (equity != null && i < equity.length) ? equity[i] : prev;
            out.put(dates[i], capital != 0 ? (e - prev) / capital : 0);
            prev = e;
        }
        return out;
    }

    private double[] weights(String mode, List<String> names, List<Map<String, Double>> metrics) {
        int n = names.size();
        double[] w = new double[n];
        switch (mode) {
            case "equal" -> Arrays.fill(w, 1.0);
            case "sharpe" -> {
                double sum = 0;
                for (int i = 0; i < n; i++) { w[i] = Math.max(0, metrics.get(i).getOrDefault("sharpe", 0.0)); sum += w[i]; }
                if (sum == 0) Arrays.fill(w, 1.0);   // no positive Sharpe anywhere -> fall back to equal
            }
            default -> {                              // "config"
                for (int i = 0; i < n; i++) w[i] = Math.max(0, strategies.weightOf(names.get(i)));
                double sum = 0; for (double v : w) sum += v;
                if (sum == 0) Arrays.fill(w, 1.0);
            }
        }
        return w;
    }
}
