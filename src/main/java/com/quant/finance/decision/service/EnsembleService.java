package com.quant.finance.decision.service;

import com.quant.finance.decision.domain.JobKind;
import com.quant.finance.decision.dto.Dtos.EnsembleLegDto;
import com.quant.finance.decision.dto.Dtos.EnsembleRequest;
import com.quant.finance.decision.dto.Dtos.EnsembleResultDto;
import com.quant.finance.decision.engine.BacktestConfig;
import com.quant.finance.decision.engine.BacktestOutput;
import com.quant.finance.decision.engine.BarResampler;
import com.quant.finance.decision.engine.PerformanceMetrics;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.error.JobCancelledException;
import com.quant.finance.decision.job.JobProgress;
import com.quant.finance.decision.job.JobProgress.Step;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Blends several strategies into one daily-rebalanced portfolio: each leg is backtested on the same universe, its equity curve becomes a daily-return stream,
 * and the streams are combined by weight ({@code config} / {@code equal} / {@code sharpe}) into one curve plus each leg's standalone metrics.
 */
@Service
@Slf4j
public class EnsembleService {

    private static final int MAX_LEGS = 40;
    private static final double CAPITAL_DEFAULT = 100_000;

    private final BacktestService backtests;
    private final StrategyService strategies;

    public EnsembleService(BacktestService backtests, StrategyService strategies) {
        this.backtests = backtests;
        this.strategies = strategies;
    }

    public EnsembleResultDto ensemble(EnsembleRequest request) {
        return ensemble(request, new JobProgress(JobKind.ENSEMBLE));
    }

    public EnsembleResultDto ensemble(EnsembleRequest request, JobProgress progress) {
        progress.begin(Step.PREPARE, 1, "Resolving strategies, symbols and settings");
        List<String> names =
                (request.strategyNames() == null || request.strategyNames().isEmpty())
                        ? new ArrayList<>(strategies.getEnabledStrategies().keySet())
                        : request.strategyNames();
        if (names.isEmpty()) {
            throw new IllegalStateException("No strategies selected and none enabled");
        }
        if (names.size() > MAX_LEGS) {
            throw new IllegalArgumentException("Too many legs (" + names.size() + "); max " + MAX_LEGS);
        }

        String weighting = (request.weighting() == null || request.weighting().isBlank())
                ? "config"
                : request.weighting().trim();
        double capital = request.capital() != null ? request.capital() : CAPITAL_DEFAULT;
        boolean perStrategyFrame = Boolean.TRUE.equals(request.perStrategyTimeframe());

        BacktestConfig baseConfig = BacktestConfig.builder()
                .capital(capital)
                .commissionBps(request.commissionBps() != null ? request.commissionBps() : 1.0)
                .slippageBps(request.slippageBps() != null ? request.slippageBps() : 2.0)
                .allowShort(request.allowShort() == null || request.allowShort())
                .positionSize(request.positionSize() != null ? request.positionSize() : 1.0)
                .timeframe(perStrategyFrame ? Timeframe.NATIVE : Timeframe.from(request.timeframe()))
                .build();

        List<String> symbols = backtests.universeOr(request.symbols());
        progress.planSymbols(symbols);
        progress.planStrategies(names);
        progress.complete(Step.PREPARE);
        // per-strategy frame: load native once and resample per leg; otherwise resample once here
        progress.begin(Step.LOAD, symbols.size(), "Loading market data");
        List<BarSeries> data = backtests.loadData(
                symbols,
                request.start(),
                request.end(),
                perStrategyFrame ? Timeframe.NATIVE : baseConfig.timeframe,
                progress);
        progress.complete(Step.LOAD);

        // --- run each leg, collect date-keyed return maps ---
        List<String> legNames = new ArrayList<>();
        List<Map<Instant, Double>> legReturns = new ArrayList<>();
        List<Map<Instant, Double>> legBenchmarkReturns = new ArrayList<>();
        List<Map<String, Double>> legMetrics = new ArrayList<>();
        TreeSet<Instant> allDates = new TreeSet<>();
        long batchStart = System.currentTimeMillis();
        log.info(
                "Ensemble: {} legs on {} symbol(s), {}-weighted ({})",
                names.size(),
                symbols.size(),
                weighting,
                perStrategyFrame ? "per-strategy timeframe" : baseConfig.timeframe.toString());
        // prep every leg single-threaded (strategy / params / timeframe / data — the DB touches),
        // then run the CPU-bound backtests in parallel on the shared pool
        record Leg(
                String name,
                TradingStrategy strategy,
                Map<String, Double> params,
                BacktestConfig config,
                List<BarSeries> data) {}
        Map<Timeframe, List<BarSeries>> dataByFrame = new HashMap<>();
        List<Leg> legJobs = new ArrayList<>();
        for (String name : names) {
            List<BarSeries> legData = data;
            BacktestConfig legConfig = baseConfig;
            if (perStrategyFrame) {
                Timeframe legFrame = strategies.recommendedTimeframe(name);
                legConfig = baseConfig.withTimeframe(legFrame);
                legData = dataByFrame.computeIfAbsent(
                        legFrame,
                        t -> data.stream().map(b -> BarResampler.resample(b, t)).toList());
            }
            legJobs.add(new Leg(name, strategies.getStrategy(name), strategies.getParams(name), legConfig, legData));
        }
        progress.checkCancelled();
        progress.beginCompute(legJobs.size(), 0, legJobs.size() + " strategy leg(s) × " + data.size() + " symbol(s)");
        List<CompletableFuture<BacktestOutput>> legFutures = new ArrayList<>();
        for (Leg leg : legJobs) {
            legFutures.add(CompletableFuture.supplyAsync(
                    () -> {
                        progress.checkCancelled(); // stopped: skip legs that have not started yet
                        progress.strategyStarted(leg.name());
                        boolean ok = false;
                        try {
                            long startMillis = System.currentTimeMillis();
                            BacktestOutput output =
                                    backtests.runOnce(leg.strategy(), leg.params(), leg.data(), leg.config());
                            log.info(
                                    "Ensemble leg '{}' @ {} computed in {} ms",
                                    leg.name(),
                                    leg.config().timeframe,
                                    System.currentTimeMillis() - startMillis);
                            ok = true;
                            return output;
                        } finally {
                            progress.strategyFinished(leg.name(), ok);
                            progress.advance(Step.COMPUTE, leg.name());
                        }
                    },
                    backtests.executor()));
        }
        for (int k = 0; k < legJobs.size(); k++) {
            progress.checkCancelled();
            BacktestOutput output = joinLeg(legFutures.get(k));
            legNames.add(legJobs.get(k).name());
            legReturns.add(toReturns(output.dates, output.equity, capital));
            legBenchmarkReturns.add(toReturns(output.dates, output.benchmark, capital));
            legMetrics.add(output.metrics);
            allDates.addAll(Arrays.asList(output.dates));
        }
        if (allDates.isEmpty()) {
            throw new IllegalStateException("Ensemble produced no dated results");
        }
        progress.complete(Step.COMPUTE);
        progress.begin(Step.SAVE, 1, "Blending " + legNames.size() + " leg(s) into one portfolio");

        double[] weights = weights(weighting, legNames, legMetrics);

        Instant[] dates = allDates.toArray(new Instant[0]);
        double[] net = new double[dates.length];
        double[] benchNet = new double[dates.length];
        for (int i = 0; i < dates.length; i++) {
            Instant date = dates[i];
            double weightSum = 0;
            double weightedReturn = 0;
            double benchmarkSum = 0;
            double benchmarkCount = 0;
            for (int k = 0; k < legNames.size(); k++) {
                Double legReturn = legReturns.get(k).get(date);
                if (legReturn != null) {
                    weightedReturn += weights[k] * legReturn;
                    weightSum += weights[k];
                }
                Double benchmarkReturn = legBenchmarkReturns.get(k).get(date);
                if (benchmarkReturn != null) {
                    benchmarkSum += benchmarkReturn;
                    benchmarkCount += 1;
                }
            }
            net[i] = weightSum > 0 ? weightedReturn / weightSum : 0;
            benchNet[i] = benchmarkCount > 0 ? benchmarkSum / benchmarkCount : 0;
        }

        double[] equity = PerformanceMetrics.equityCurve(net, capital);
        double[] benchmarkEquity = PerformanceMetrics.equityCurve(benchNet, capital);
        double[] drawdown = PerformanceMetrics.drawdown(equity);
        Map<String, Double> metrics = PerformanceMetrics.returnMetrics(net, capital, 0, dates);

        double totalWeight = 0;
        for (double weight : weights) {
            totalWeight += weight;
        }
        List<EnsembleLegDto> legs = new ArrayList<>();
        for (int k = 0; k < legNames.size(); k++) {
            legs.add(new EnsembleLegDto(
                    legNames.get(k),
                    totalWeight > 0 ? Math.round(weights[k] / totalWeight * 1e4) / 1e4 : 0,
                    legMetrics.get(k)));
        }
        legs.sort((a, b) -> Double.compare(b.weight(), a.weight()));

        List<String> dateStrings = new ArrayList<>(dates.length);
        for (Instant date : dates) {
            dateStrings.add(date.toString());
        }
        String timeframeLabel = perStrategyFrame ? "PER_STRATEGY" : baseConfig.timeframe.name();
        log.info(
                "Ensemble: {} legs done in {} ms — blended return={}% sharpe={}",
                names.size(),
                System.currentTimeMillis() - batchStart,
                metrics.getOrDefault("totalReturnPct", 0.0),
                metrics.getOrDefault("sharpe", 0.0));
        progress.complete(Step.SAVE);
        return new EnsembleResultDto(
                symbols,
                dates[0],
                dates[dates.length - 1],
                timeframeLabel,
                dates.length,
                weighting,
                metrics,
                dateStrings,
                equity,
                benchmarkEquity,
                drawdown,
                legs);
    }

    /** Joins a leg, surfacing a stop request (thrown inside the pool) as itself rather than as a wrapper. */
    private static BacktestOutput joinLeg(CompletableFuture<BacktestOutput> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof JobCancelledException cancelled) {
                throw cancelled;
            }
            throw e;
        }
    }

    /**
     * Per-bar net return of a leg, recovered from its (additive, fixed-notional) equity path:
     * {@code (equity[i] - equity[i-1]) / capital}.
     */
    private static Map<Instant, Double> toReturns(Instant[] dates, double[] equity, double capital) {
        Map<Instant, Double> out = new HashMap<>();
        double previousEquity = capital;
        for (int i = 0; i < dates.length; i++) {
            double currentEquity = (equity != null && i < equity.length) ? equity[i] : previousEquity;
            out.put(dates[i], capital != 0 ? (currentEquity - previousEquity) / capital : 0);
            previousEquity = currentEquity;
        }
        return out;
    }

    private double[] weights(String mode, List<String> names, List<Map<String, Double>> metrics) {
        int legCount = names.size();
        double[] result = new double[legCount];
        switch (mode) {
            case "equal" -> Arrays.fill(result, 1.0);
            case "sharpe" -> {
                double sum = 0;
                for (int i = 0; i < legCount; i++) {
                    result[i] = Math.max(0, metrics.get(i).getOrDefault("sharpe", 0.0));
                    sum += result[i];
                }
                if (sum == 0) {
                    Arrays.fill(result, 1.0); // no positive Sharpe anywhere -> fall back to equal
                }
            }
            default -> { // "config"
                for (int i = 0; i < legCount; i++) {
                    result[i] = Math.max(0, strategies.weightOf(names.get(i)));
                }
                double sum = 0;
                for (double value : result) {
                    sum += value;
                }
                if (sum == 0) {
                    Arrays.fill(result, 1.0);
                }
            }
        }
        return result;
    }
}
