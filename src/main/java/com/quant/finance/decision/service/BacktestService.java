package com.quant.finance.decision.service;

import com.quant.finance.decision.config.ApplicationProperties;
import com.quant.finance.decision.config.ExecutorConfig;
import com.quant.finance.decision.data.MarketDataService;
import com.quant.finance.decision.domain.JobKind;
import com.quant.finance.decision.dto.Dtos.BacktestRequest;
import com.quant.finance.decision.dto.Dtos.BacktestResultDto;
import com.quant.finance.decision.dto.Dtos.LeaderboardEntryDto;
import com.quant.finance.decision.dto.Dtos.PairsRequest;
import com.quant.finance.decision.dto.Dtos.PruneResultDto;
import com.quant.finance.decision.dto.Dtos.SymbolResultDto;
import com.quant.finance.decision.dto.Dtos.TimeframeDto;
import com.quant.finance.decision.dto.Dtos.TradeDetailDto;
import com.quant.finance.decision.dto.Dtos.TradePageDto;
import com.quant.finance.decision.engine.BacktestConfig;
import com.quant.finance.decision.engine.BacktestOutput;
import com.quant.finance.decision.engine.Backtester;
import com.quant.finance.decision.engine.BarResampler;
import com.quant.finance.decision.engine.SymbolResult;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.engine.TradeResult;
import com.quant.finance.decision.entity.BacktestResultEntity;
import com.quant.finance.decision.entity.BacktestRunEntity;
import com.quant.finance.decision.entity.TradeEntity;
import com.quant.finance.decision.job.JobProgress;
import com.quant.finance.decision.job.JobProgress.Step;
import com.quant.finance.decision.repository.BacktestResultRepository;
import com.quant.finance.decision.repository.BacktestRunRepository;
import com.quant.finance.decision.repository.TradeRepository;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import com.quant.finance.decision.strategy.impl.PairsStrategy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class BacktestService {

    /** A single named strategy may lever up to 5×; a batch sweep is pinned at 1× so
     *  transaction-cost drag isn't multiplied across every strategy at once. */
    private static final double SINGLE_RUN_MAX_POSITION = 5.0;

    private static final double MULTI_RUN_MAX_POSITION = 1.0;
    /** A batch run never executes on 1-minute bars — AUTO fans out per strategy, an
     *  explicit "Native" is lifted to this floor. */
    private static final Timeframe RUN_ALL_MIN_FRAME = Timeframe.M15;

    private static String formatMetrics(Map<String, Double> metrics) {
        return String.format(
                "return=%.1f%% sharpe=%.2f maxDD=%.1f%% trades=%.0f",
                metrics.getOrDefault("totalReturnPct", 0.0),
                metrics.getOrDefault("sharpe", 0.0),
                metrics.getOrDefault("maxDrawdownPct", 0.0),
                metrics.getOrDefault("trades", 0.0));
    }

    private final MarketDataService marketData;
    private final StrategyService strategies;
    private final UniverseService universe;
    private final JsonCodec json;
    private final BacktestRunRepository runRepo;
    private final BacktestResultRepository resultRepo;
    private final TradeRepository tradeRepo;
    private final ExecutorService executor;
    private final Backtester backtester;
    private final ApplicationProperties properties;

    public BacktestService(
            MarketDataService marketData,
            StrategyService strategies,
            UniverseService universe,
            JsonCodec json,
            BacktestRunRepository runRepo,
            BacktestResultRepository resultRepo,
            TradeRepository tradeRepo,
            @org.springframework.beans.factory.annotation.Qualifier(ExecutorConfig.BACKTEST_EXECUTOR)
                    ExecutorService executor,
            ApplicationProperties properties) {
        this.backtester = new Backtester(properties.getBacktest().isTracePerSymbol());
        this.properties = properties;
        this.marketData = marketData;
        this.strategies = strategies;
        this.universe = universe;
        this.json = json;
        this.runRepo = runRepo;
        this.resultRepo = resultRepo;
        this.tradeRepo = tradeRepo;
        this.executor = executor;
    }

    /** Cap on how far back a run-all / batch backtest may reach (heap guard on 1-min data); 400 when not set. */
    private int runAllMaxDays() {
        int days = properties.getBacktest().getRunAllMaxDays();
        return days > 0 ? days : 400;
    }

    /** Resample targets offered by the backtest form. */
    public List<TimeframeDto> timeframes() {
        List<TimeframeDto> out = new ArrayList<>();
        for (Timeframe frame : Timeframe.values()) {
            out.add(new TimeframeDto(frame.name(), frame.label, frame.isNative()));
        }
        return out;
    }

    /**
     * Resolve the resample frame: an explicit value wins; "AUTO"/blank falls back to the
     * named strategy's recommended frame (or NATIVE when the target isn't a single strategy).
     */
    private Timeframe resolveTimeframe(String strategyName, String reqTimeframe) {
        if (!Timeframe.isAuto(reqTimeframe)) {
            return Timeframe.from(reqTimeframe);
        }
        return strategies.isStrategy(strategyName) ? strategies.recommendedTimeframe(strategyName) : Timeframe.NATIVE;
    }

    /** A batch run never executes on 1-min bars: NATIVE is lifted to the floor frame. */
    private static Timeframe batchFrame(Timeframe frame) {
        return frame == null || frame.isNative() ? RUN_ALL_MIN_FRAME : frame;
    }

    private BacktestConfig configOf(BacktestRequest request, double maxPositionSize) {
        BacktestConfig.Builder builder = BacktestConfig.builder()
                .capital(request.capital() != null ? request.capital() : 100_000)
                .commissionBps(request.commissionBps() != null ? request.commissionBps() : 1.0)
                .slippageBps(request.slippageBps() != null ? request.slippageBps() : 2.0)
                .allowShort(request.allowShort() == null || request.allowShort())
                .timeframe(resolveTimeframe(request.strategyName(), request.timeframe()));
        if (request.execLag() != null) {
            builder.execLag(request.execLag());
        }
        if (request.positionSize() != null) {
            double positionSize = Math.min(request.positionSize(), maxPositionSize);
            if (positionSize < request.positionSize()) {
                log.info(
                        "Config[{}]: positionSize {}× capped to {}× (batch run)",
                        request.strategyName() == null ? "ALL" : request.strategyName(),
                        request.positionSize(),
                        positionSize);
            }
            builder.positionSize(positionSize);
        }
        if (request.riskFreePct() != null) {
            builder.riskFreePct(request.riskFreePct());
        }
        if (request.warmupBars() != null) {
            builder.warmupBars(request.warmupBars());
        }
        // unset SL/TP on a single named strategy falls back to its own saved default
        // (same "AUTO" convention as timeframe) — a batch/ALL request resolves this per-job instead.
        Double stopLossPct = request.stopLossPct();
        if (stopLossPct == null && strategies.isStrategy(request.strategyName())) {
            stopLossPct = strategies.defaultStopLossPct(request.strategyName());
        }
        if (stopLossPct != null) {
            builder.stopLossPct(stopLossPct);
        }
        Double takeProfitPct = request.takeProfitPct();
        if (takeProfitPct == null && strategies.isStrategy(request.strategyName())) {
            takeProfitPct = strategies.defaultTakeProfitPct(request.strategyName());
        }
        if (takeProfitPct != null) {
            builder.takeProfitPct(takeProfitPct);
        }
        BacktestConfig config = builder.build();
        log.info(
                "Config[{}]: tf={} tfReq={} capital={} comm={}bps slip={}bps posSize={} execLag={} SL={}% TP={}% warmup={} rf={}% allowShort={}",
                request.strategyName(),
                config.timeframe,
                request.timeframe() == null ? "AUTO" : request.timeframe(),
                config.capital,
                config.commissionBps,
                config.slippageBps,
                config.positionSize,
                config.execLag,
                config.stopLossPct,
                config.takeProfitPct,
                config.warmupBars,
                config.riskFreePct,
                config.allowShort);
        return config;
    }

    private List<String> resolveSymbols(List<String> requested) {
        if (requested != null && !requested.isEmpty()) {
            List<String> resolved = requested.stream().map(String::toUpperCase).toList();
            log.info("Symbols: {} requested {}", resolved.size(), preview(resolved));
            return resolved;
        }
        List<String> resolved = universe.get();
        log.info("Symbols: {} from universe {}", resolved.size(), preview(resolved));
        return resolved;
    }

    private static double orZero(Double value) {
        return value == null ? 0 : value;
    }

    private static String preview(List<String> items) {
        return items.size() <= 12 ? items.toString() : items.subList(0, 12) + " …+" + (items.size() - 12);
    }

    private List<BarSeries> load(List<String> symbols, LocalDate start, LocalDate end, Timeframe frame) {
        return load(symbols, start, end, frame, new JobProgress(JobKind.RUN));
    }

    private List<BarSeries> load(
            List<String> symbols, LocalDate start, LocalDate end, Timeframe frame, JobProgress progress) {
        long startMillis = System.currentTimeMillis();
        log.info("Load bars: {} symbol(s) {}..{} @ {}", symbols.size(), start, end, frame);
        List<BarSeries> out = new ArrayList<>();
        int miss = 0;
        long bars = 0;
        for (String symbol : symbols) {
            progress.checkCancelled();
            BarSeries symbolBars = marketData.getBars(symbol, start, end);
            if (symbolBars.size() > 0) {
                BarSeries resampled = BarResampler.resample(symbolBars, frame);
                out.add(resampled);
                bars += resampled.size();
            } else {
                miss++;
            }
            progress.symbolLoaded(symbol, symbolBars.size() > 0);
        }
        log.info(
                "Load bars: {} series ready, {} bars total{} in {} ms",
                out.size(),
                bars,
                miss > 0 ? " (" + miss + " symbol(s) had no data)" : "",
                System.currentTimeMillis() - startMillis);
        if (out.isEmpty()) {
            log.warn("Load bars: no market data for {} @ {} {}..{}", preview(symbols), frame, start, end);
            throw new IllegalStateException("No market data for requested symbols/range");
        }
        return out;
    }

    /**
     * Loads native bars once per symbol and resamples each into every requested frame, holding one symbol's native series at a time
     * (1-min native is ~1.9M bars in a batch, so this keeps the working set an order of magnitude smaller).
     */
    private Map<Timeframe, List<BarSeries>> loadResampled(
            List<String> symbols, LocalDate start, LocalDate end, Set<Timeframe> frames, JobProgress progress) {
        long startMillis = System.currentTimeMillis();
        Map<Timeframe, List<BarSeries>> out = new LinkedHashMap<>();
        for (Timeframe frame : frames) {
            out.put(frame, new ArrayList<>());
        }
        long bars = 0;
        int miss = 0;
        for (String symbol : symbols) {
            progress.checkCancelled();
            BarSeries nativeB = marketData.getBars(symbol, start, end);
            if (nativeB.size() == 0) {
                miss++;
                progress.symbolLoaded(symbol, false);
                continue;
            }
            for (Timeframe frame : frames) {
                BarSeries resampled = BarResampler.resample(nativeB, frame);
                out.get(frame).add(resampled);
                bars += resampled.size();
            }
            progress.symbolLoaded(symbol, true);
        }
        log.info(
                "Load bars: {} symbol(s) -> {} frame(s) {}, {} resampled bars total{} in {} ms",
                symbols.size(),
                frames.size(),
                frames,
                bars,
                miss > 0 ? " (" + miss + " symbol(s) had no data)" : "",
                System.currentTimeMillis() - startMillis);
        if (out.values().stream().allMatch(List::isEmpty)) {
            log.warn("Load bars: no market data for {} {}..{}", preview(symbols), start, end);
            throw new IllegalStateException("No market data for requested symbols/range");
        }
        return out;
    }

    // ---- shared entry points for the optimizer / ensemble services --------

    /** Requested symbols upper-cased, or the saved universe when none are given. */
    public List<String> universeOr(List<String> requested) {
        return resolveSymbols(requested);
    }

    /** Load + resample bars once so a sweep can reuse them across many runs. */
    public List<BarSeries> loadData(List<String> symbols, LocalDate start, LocalDate end, Timeframe frame) {
        return load(symbols, start, end, frame);
    }

    /** As {@link #loadData(List, LocalDate, LocalDate, Timeframe)}, reporting each symbol to {@code progress}. */
    public List<BarSeries> loadData(
            List<String> symbols, LocalDate start, LocalDate end, Timeframe frame, JobProgress progress) {
        return load(symbols, start, end, frame, progress);
    }

    public BacktestConfig configOf(BacktestRequest request) {
        return configOf(request, SINGLE_RUN_MAX_POSITION);
    }

    /** Shared backtest thread pool — for the optimiser / ensemble to fan out their own work. */
    public ExecutorService executor() {
        return executor;
    }

    /** Run one portfolio backtest against already-loaded data and return only its metrics (no persistence). */
    public Map<String, Double> evaluate(
            TradingStrategy strategy, Map<String, Double> params, List<BarSeries> data, BacktestConfig config) {
        BacktestOutput output = backtester.runPortfolio(
                data, strategy, params == null || params.isEmpty() ? null : params, config, null, false);
        return output.metrics;
    }

    /** Full portfolio output against already-loaded data (no persistence) — for the ensemble blend. */
    public BacktestOutput runOnce(
            TradingStrategy strategy, Map<String, Double> params, List<BarSeries> data, BacktestConfig config) {
        return backtester.runPortfolio(
                data, strategy, params == null || params.isEmpty() ? null : params, config, null, false);
    }

    @Transactional
    public BacktestResultDto run(BacktestRequest request) {
        return run(request, new JobProgress(JobKind.RUN));
    }

    @Transactional
    public BacktestResultDto run(BacktestRequest request, JobProgress progress) {
        progress.begin(Step.PREPARE, 1, "Resolving symbols and settings");
        List<String> symbols = resolveSymbols(request.symbols());
        BacktestConfig config = configOf(request);
        TradingStrategy strategy = strategies.getStrategy(request.strategyName());
        Map<String, Double> params = strategies.getParams(request.strategyName());
        progress.planSymbols(symbols);
        progress.planStrategies(List.of(request.strategyName()));
        progress.complete(Step.PREPARE);
        long startMillis = System.currentTimeMillis();
        log.info(
                "Backtest: '{}' on {} symbol(s) {}..{} @ {}",
                request.strategyName(),
                symbols.size(),
                request.start(),
                request.end(),
                config.timeframe);
        progress.begin(Step.LOAD, symbols.size(), "Loading market data");
        List<BarSeries> data = load(symbols, request.start(), request.end(), config.timeframe, progress);
        progress.complete(Step.LOAD);
        progress.checkCancelled();
        log.info(
                "Backtest: '{}' computing portfolio over {} series (symbols in parallel)…",
                request.strategyName(),
                data.size());
        progress.beginCompute(data.size(), 1, "Computing " + data.size() + " symbol(s)");
        progress.strategyStarted(request.strategyName());
        BacktestOutput output = backtester.runPortfolio(
                data, strategy, params.isEmpty() ? null : params, config, executor, true, progress::symbolComputed);
        progress.strategyFinished(request.strategyName(), true);
        progress.complete(Step.COMPUTE);
        progress.checkCancelled();
        progress.begin(Step.SAVE, 1, "Saving the run, its results and trades");
        BacktestResultDto dto = persist(output, symbols, config, request.start(), request.end());
        progress.complete(Step.SAVE);
        log.info(
                "Backtest: '{}' done in {} ms — run #{} {}",
                request.strategyName(),
                System.currentTimeMillis() - startMillis,
                dto.runId(),
                formatMetrics(output.metrics));
        return dto;
    }

    @Transactional
    public List<LeaderboardEntryDto> runAll(BacktestRequest request) {
        return runAll(request, new JobProgress(JobKind.RUN_ALL));
    }

    @Transactional
    public List<LeaderboardEntryDto> runAll(BacktestRequest request, JobProgress progress) {
        progress.begin(Step.PREPARE, 1, "Resolving symbols, strategies and settings");
        List<String> symbols = resolveSymbols(request.symbols());

        // cap the look-back window — hundreds of strategies over years of 1-min bars is what OOMs the box
        LocalDate start = request.start();
        LocalDate end = request.end();
        int maxDays = runAllMaxDays();
        if (start != null && end != null && start.isBefore(end.minusDays(maxDays))) {
            LocalDate capped = end.minusDays(maxDays);
            log.info(
                    "Backtest run-all: window {}..{} exceeds the {}-day batch cap — starting from {}",
                    start,
                    end,
                    maxDays,
                    capped);
            start = capped;
        }

        BacktestConfig batchConfig = configOf(request, MULTI_RUN_MAX_POSITION);
        // AUTO (or an explicit "Native") on a batch run must never execute on 1-min bars:
        // AUTO fans each strategy out on its own recommended frame; an explicit Native is
        // lifted to the batch floor frame.
        boolean perStrategyTf =
                Boolean.TRUE.equals(request.perStrategyTimeframe()) || Timeframe.isAuto(request.timeframe());
        Timeframe sharedFrame = batchConfig.timeframe.isNative() ? RUN_ALL_MIN_FRAME : batchConfig.timeframe;

        boolean includeDisabled = Boolean.TRUE.equals(request.includeDisabled());
        List<String> picked = request.strategyNames();
        String scope;
        Map<String, TradingStrategy> enabled;
        if (picked != null && !picked.isEmpty()) {
            enabled = strategies.getStrategies(picked);
            scope = "picked (" + enabled.size() + "/" + picked.size() + ")";
        } else if (includeDisabled) {
            enabled = strategies.getRunnableStrategies();
            scope = "runnable (incl. disabled)";
        } else {
            enabled = strategies.getEnabledStrategies();
            scope = "enabled";
        }
        long batchStart = System.currentTimeMillis();
        if (enabled.isEmpty()) {
            throw new IllegalStateException(
                    picked != null && !picked.isEmpty()
                            ? "None of the picked strategies are runnable (unknown or archived)."
                            : includeDisabled
                                    ? "No strategies to run — every strategy is archived."
                                    : "No enabled strategies — enable some on the Strategies tab, or use 'include disabled'.");
        }

        // which resample frames does this batch actually need? (a per-strategy frame that
        // resolves to Native is lifted to the batch floor — no batch runs on 1-min bars)
        Set<Timeframe> frames = new LinkedHashSet<>();
        if (perStrategyTf) {
            for (String name : enabled.keySet()) {
                frames.add(batchFrame(strategies.recommendedTimeframe(name)));
            }
        } else {
            frames.add(sharedFrame);
        }

        log.info(
                "Backtest run-all: {} {} strategies on {} symbol(s) {}..{} ({})",
                enabled.size(),
                scope,
                symbols.size(),
                start,
                end,
                perStrategyTf ? "per-strategy timeframe " + frames : sharedFrame.toString());

        progress.planSymbols(symbols);
        progress.planStrategies(new ArrayList<>(enabled.keySet()));

        // load native bars once per symbol, resample into every needed frame, drop the native bars
        progress.complete(Step.PREPARE);
        progress.begin(Step.LOAD, symbols.size(), "Loading market data");
        Map<Timeframe, List<BarSeries>> tfData = loadResampled(symbols, start, end, frames, progress);
        progress.complete(Step.LOAD);

        // unset SL/TP on a batch request falls back to each strategy's own saved default
        // (same "AUTO" convention as timeframe); an explicit value overrides uniformly for all.
        boolean autoSL = request.stopLossPct() == null;
        boolean autoTP = request.takeProfitPct() == null;

        // --- prep one job per strategy (single-threaded: resolves params / timeframe / data, no compute) ---
        record Job(
                String name,
                TradingStrategy strategy,
                Map<String, Double> params,
                BacktestConfig config,
                List<BarSeries> data) {}
        List<Job> jobs = new ArrayList<>();
        for (Map.Entry<String, TradingStrategy> entry : enabled.entrySet()) {
            Map<String, Double> params = strategies.getParams(entry.getKey());
            Timeframe frame = perStrategyTf ? batchFrame(strategies.recommendedTimeframe(entry.getKey())) : sharedFrame;
            BacktestConfig runCfg = batchConfig.timeframe == frame ? batchConfig : batchConfig.withTimeframe(frame);
            if (autoSL || autoTP) {
                double sl = autoSL ? orZero(strategies.defaultStopLossPct(entry.getKey())) : runCfg.stopLossPct;
                double tp = autoTP ? orZero(strategies.defaultTakeProfitPct(entry.getKey())) : runCfg.takeProfitPct;
                runCfg = runCfg.withRisk(sl, tp);
            }
            jobs.add(new Job(
                    entry.getKey(), entry.getValue(), params.isEmpty() ? null : params, runCfg, tfData.get(frame)));
        }
        log.info(
                "Backtest run-all: {} jobs prepared ({} distinct timeframe(s)) — submitting to pool",
                jobs.size(),
                frames.size());

        // --- fan the CPU-bound backtests out across the pool (no DB in the tasks) ---
        // one strategy failing (bad data, indicator NaN, …) must not abort the whole batch
        int total = jobs.size();
        int units = 0;
        for (Job job : jobs) {
            units += job.data().size(); // one unit per (strategy, loaded symbol)
        }
        progress.beginCompute(units, total, total + " strategies × " + (units / Math.max(1, total)) + " symbol(s)");
        List<CompletableFuture<BacktestOutput>> futures = new ArrayList<>(total);
        for (Job job : jobs) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> {
                        if (progress.isCancelled()) {
                            return null; // stopped: skip work that has not started yet
                        }
                        progress.strategyStarted(job.name());
                        boolean ok = false;
                        try {
                            long startMillis = System.currentTimeMillis();
                            BacktestOutput output = backtester.runPortfolio(
                                    job.data(),
                                    job.strategy(),
                                    job.params(),
                                    job.config(),
                                    null,
                                    true,
                                    progress::symbolComputed);
                            log.info(
                                    "Backtest run-all: '{}' @ {} computed in {} ms — {}",
                                    job.name(),
                                    job.config().timeframe,
                                    System.currentTimeMillis() - startMillis,
                                    formatMetrics(output.metrics));
                            ok = true;
                            return output;
                        } catch (RuntimeException ex) {
                            log.warn(
                                    "Backtest run-all: '{}' failed — {}: {}",
                                    job.name(),
                                    ex.getClass().getSimpleName(),
                                    ex.getMessage());
                            return null;
                        } finally {
                            progress.strategyFinished(job.name(), ok);
                        }
                    },
                    executor));
        }

        // --- persist sequentially on this transaction thread ---
        progress.begin(Step.SAVE, total, "Waiting for the first strategy");
        List<LeaderboardEntryDto> board = new ArrayList<>(total);
        int failed = 0;
        for (int i = 0; i < total; i++) {
            progress.checkCancelled();
            Job job = jobs.get(i);
            BacktestOutput output = futures.get(i).join();
            futures.set(i, null); // release the output once we've persisted / skipped it
            progress.checkCancelled();
            if (output == null) {
                failed++;
                progress.advance(Step.SAVE, job.name() + " failed (" + (i + 1) + "/" + total + ")");
                continue;
            }
            BacktestResultDto dto = persist(output, symbols, job.config(), start, end);
            board.add(new LeaderboardEntryDto(
                    dto.runId(), dto.strategy(), dto.timeframe(), dto.bars(), dto.symbols(), dto.metrics()));
            progress.advance(Step.SAVE, "Saved " + job.name() + " (" + (i + 1) + "/" + total + ")");
        }
        progress.complete(Step.COMPUTE);
        progress.complete(Step.SAVE);
        board.sort((a, b) -> Double.compare(
                b.metrics().getOrDefault("sharpe", 0.0), a.metrics().getOrDefault("sharpe", 0.0)));
        log.info(
                "Backtest run-all: {} of {} strategies done in {} ms (parallel){}",
                board.size(),
                total,
                System.currentTimeMillis() - batchStart,
                failed > 0 ? " — " + failed + " failed" : "");
        return board;
    }

    @Transactional
    public BacktestResultDto runPairs(PairsRequest request) {
        return runPairs(request, new JobProgress(JobKind.PAIRS));
    }

    @Transactional
    public BacktestResultDto runPairs(PairsRequest request, JobProgress progress) {
        progress.begin(Step.PREPARE, 1, "Preparing the pair");
        BacktestConfig.Builder builder = BacktestConfig.builder()
                .capital(request.capital() != null ? request.capital() : 100_000)
                .commissionBps(request.commissionBps() != null ? request.commissionBps() : 1.0)
                .slippageBps(request.slippageBps() != null ? request.slippageBps() : 2.0)
                .allowShort(true)
                .timeframe(Timeframe.from(request.timeframe()));
        if (request.positionSize() != null) {
            builder.positionSize(request.positionSize());
        }
        if (request.stopLossPct() != null) {
            builder.stopLossPct(request.stopLossPct());
        }
        if (request.takeProfitPct() != null) {
            builder.takeProfitPct(request.takeProfitPct());
        }
        BacktestConfig config = builder.build();
        String symA = request.symbolA().toUpperCase();
        String symB = request.symbolB().toUpperCase();
        progress.planSymbols(List.of(symA, symB));
        progress.complete(Step.PREPARE);

        long startMillis = System.currentTimeMillis();
        log.info(
                "Backtest: pairs {}/{} {}..{} @ {}",
                request.symbolA(),
                request.symbolB(),
                request.start(),
                request.end(),
                config.timeframe);
        progress.begin(Step.LOAD, 2, "Loading market data");
        progress.checkCancelled();
        BarSeries rawA = marketData.getBars(symA, request.start(), request.end());
        progress.symbolLoaded(symA, rawA.size() > 0);
        progress.checkCancelled();
        BarSeries rawB = marketData.getBars(symB, request.start(), request.end());
        progress.symbolLoaded(symB, rawB.size() > 0);
        BarSeries seriesA = BarResampler.resample(rawA, config.timeframe);
        BarSeries seriesB = BarResampler.resample(rawB, config.timeframe);
        progress.complete(Step.LOAD);
        progress.checkCancelled();
        PairsStrategy strategy = new PairsStrategy(
                request.window() != null ? request.window() : 60,
                request.entry() != null ? request.entry() : 2.0,
                request.exit() != null ? request.exit() : 0.5);
        progress.beginCompute(1, 0, "Backtesting the pair");
        BacktestOutput output = backtester.runPairs(seriesA, seriesB, strategy, config);
        progress.complete(Step.COMPUTE);
        progress.checkCancelled();
        progress.begin(Step.SAVE, 1, "Saving the run, its results and trades");
        BacktestResultDto dto = persist(output, List.of(symA, symB), config, request.start(), request.end());
        progress.complete(Step.SAVE);
        log.info(
                "Backtest: pairs {}/{} done in {} ms — run #{} {}",
                request.symbolA(),
                request.symbolB(),
                System.currentTimeMillis() - startMillis,
                dto.runId(),
                formatMetrics(output.metrics));
        return dto;
    }

    private BacktestResultDto persist(
            BacktestOutput output, List<String> symbols, BacktestConfig config, LocalDate start, LocalDate end) {
        BacktestRunEntity run = new BacktestRunEntity();
        run.setStrategyName(output.strategy);
        run.setSymbolsCsv(String.join(",", symbols));
        run.setStartDate(output.startDate);
        run.setEndDate(output.endDate);
        run.setCapital(config.capital);
        run.setCommissionBps(config.commissionBps);
        run.setSlippageBps(config.slippageBps);
        run.setAllowShort(config.allowShort);
        run.setTimeframe(config.timeframe.name());
        run.setExecLag(config.execLag);
        run.setPositionSize(config.positionSize);
        run.setRiskFreePct(config.riskFreePct);
        run.setWarmupBars(config.warmupBars);
        run.setStopLossPct(config.stopLossPct);
        run.setTakeProfitPct(config.takeProfitPct);
        run.setBars(output.dates.length);
        applySummary(run, output.metrics);
        run.setStatus("COMPLETED");
        run = runRepo.save(run);

        BacktestResultEntity result = new BacktestResultEntity();
        result.setRun(run);
        result.setMetricsJson(json.write(output.metrics));
        result.setDatesJson(json.write(datesToStrings(output.dates)));
        result.setEquityJson(json.write(output.equity));
        result.setBenchmarkJson(json.write(output.benchmark));
        result.setDrawdownJson(json.write(output.drawdown));
        result.setSymbolReturnsJson(json.write(output.symbolReturnsPct));
        result.setSymbolDetailsJson(json.write(new SymbolDetails(output.yearlyReturnsPct, output.symbolResults)));
        resultRepo.save(result);

        List<TradeEntity> trades = new ArrayList<>();
        for (TradeResult trade : output.trades) {
            TradeEntity te = new TradeEntity();
            te.setRun(run);
            te.setSymbol(trade.symbol);
            te.setSide(trade.side);
            te.setEntryDate(trade.entryDate);
            te.setExitDate(trade.exitDate);
            te.setEntryPx(trade.entryPx);
            te.setExitPx(trade.exitPx);
            te.setBars(trade.bars);
            te.setReturnPct(trade.returnPct);
            te.setGrossReturn(trade.grossReturn);
            te.setCost(trade.cost);
            te.setNetReturn(trade.netReturn);
            te.setExposure(trade.exposure);
            te.setStillOpen(trade.open);
            trades.add(te);
        }
        tradeRepo.saveAll(trades);
        log.info(
                "Persisted run #{}: '{}' @ {} — {} symbols, {} bars, {} trades",
                run.getId(),
                output.strategy,
                config.timeframe.name(),
                symbols.size(),
                output.dates.length,
                trades.size());

        return toDto(run.getId(), config.timeframe.name(), output.dates.length, output);
    }

    public BacktestResultDto getRun(Long runId) {
        log.info("Backtest: loading run #{}", runId);
        BacktestRunEntity run =
                runRepo.findById(runId).orElseThrow(() -> new NoSuchElementException("No run " + runId));
        BacktestResultEntity result = resultRepo
                .findByRunId(runId)
                .orElseThrow(() -> new NoSuchElementException("No result for run " + runId));
        // runs saved before per-symbol results existed have no details document: empty, not an error
        SymbolDetails details = json.read(result.getSymbolDetailsJson(), SymbolDetails.class);
        List<String> symbols = run.getSymbolsCsv() == null
                ? List.of()
                : Arrays.asList(run.getSymbolsCsv().split(","));
        List<String> dates = readStrings(result.getDatesJson());
        String frame = run.getTimeframe() != null ? run.getTimeframe() : Timeframe.NATIVE.name();
        int bars = run.getBars() != null ? run.getBars() : dates.size();
        return new BacktestResultDto(
                runId,
                run.getStrategyName(),
                symbols,
                run.getStartDate(),
                run.getEndDate(),
                frame,
                bars,
                json.readDoubleMap(result.getMetricsJson()),
                dates,
                json.readDoubles(result.getEquityJson()),
                json.readDoubles(result.getBenchmarkJson()),
                json.readDoubles(result.getDrawdownJson()),
                json.readDoubleMap(result.getSymbolReturnsJson()),
                details == null ? List.of() : symbolResultDtos(details.symbols()),
                details == null || details.portfolioYearlyReturnsPct() == null
                        ? Map.of()
                        : details.portfolioYearlyReturnsPct(),
                tradeRepo.countByRunId(runId));
    }

    /**
     * One page of a run's trades, optionally for one symbol / side, with totals over everything
     * that matches. Loaded whole and paged in memory (see {@link TradeDetails}).
     */
    public TradePageDto getTrades(
            Long runId, String symbol, String side, String sort, String dir, Integer page, Integer size) {
        List<TradeDetailDto> all = tradeDetails(requireRun(runId));
        return TradeDetails.page(
                all,
                symbol,
                side,
                sort,
                dir,
                page == null ? 0 : page,
                size == null ? TradeDetails.DEFAULT_PAGE_SIZE : size);
    }

    /** Every trade of the run matching the filter, in the requested order, as the bytes of an .xlsx workbook. */
    public byte[] exportTrades(Long runId, String symbol, String side, String sort, String dir) {
        BacktestRunEntity run = requireRun(runId);
        List<TradeDetailDto> rows = TradeDetails.select(tradeDetails(run), symbol, side, sort, dir);
        log.info("Backtest: exporting {} trades of run #{} to Excel", rows.size(), runId);
        return TradeWorkbook.toBytes(run, symbol, side, rows);
    }

    private BacktestRunEntity requireRun(Long runId) {
        return runRepo.findById(runId).orElseThrow(() -> new NoSuchElementException("No run " + runId));
    }

    private List<TradeDetailDto> tradeDetails(BacktestRunEntity run) {
        TradeDetails.RunCosts costs = new TradeDetails.RunCosts(
                run.getCapital(),
                run.getCommissionBps(),
                run.getSlippageBps(),
                run.getPositionSize() != null ? run.getPositionSize() : 1.0);
        return tradeRepo.findByRunId(run.getId()).stream()
                .map(t -> TradeDetails.toDto(t, costs))
                .toList();
    }

    /** What {@code backtest_result.symbol_details_json} holds. */
    public record SymbolDetails(Map<String, Double> portfolioYearlyReturnsPct, Map<String, SymbolResult> symbols) {}

    private static List<SymbolResultDto> symbolResultDtos(Map<String, SymbolResult> results) {
        if (results == null) {
            return List.of();
        }
        List<SymbolResultDto> out = new ArrayList<>(results.size());
        results.forEach((sym, r) -> out.add(new SymbolResultDto(sym, r.metrics(), r.yearlyReturnsPct())));
        return out;
    }

    @Transactional
    public void deleteRun(Long runId) {
        if (!runRepo.existsById(runId)) {
            throw new NoSuchElementException("No run " + runId);
        }
        tradeRepo.deleteByRunId(runId);
        resultRepo.deleteByRunId(runId);
        runRepo.deleteById(runId);
        log.info("Backtest: deleted run #{}", runId);
    }

    /**
     * Ranks strategies with run history by the average {@code by} metric of their {@code recentRuns} latest runs, keeps the top {@code keep} / {@code keepPct}%,
     * and deletes the rest's runs/results/trades, then archives, disables or (mode="delete", irreversible) removes them; no-history strategies are untouched.
     */
    @Transactional
    public PruneResultDto pruneToTop(Integer keep, Integer keepPct, Integer recentRuns, String by, String mode) {
        boolean hardDelete = "delete".equalsIgnoreCase(mode);
        boolean archive = !hardDelete && !"disable".equalsIgnoreCase(mode);
        boolean bySharpe = by != null && by.equalsIgnoreCase("sharpe");
        String rankedBy = bySharpe ? "sharpe" : "totalReturnPct";
        int window = recentRuns == null || recentRuns < 1 ? 2 : recentRuns;

        // avg of the last `window` runs' metric, per strategy
        record Scored(String name, double score) {}
        List<Scored> ranked = new ArrayList<>();
        for (String name : runRepo.distinctStrategyNames()) {
            List<BacktestRunEntity> runs = runRepo.findByStrategyNameOrderByCreatedAtDesc(name);
            double sum = 0;
            int count = 0;
            for (BacktestRunEntity run : runs) {
                if (count >= window) {
                    break;
                }
                Double score = bySharpe ? run.getSharpe() : run.getTotalReturnPct();
                if (score != null && !score.isNaN()) {
                    sum += score;
                    count++;
                }
            }
            ranked.add(new Scored(name, count > 0 ? sum / count : Double.NEGATIVE_INFINITY));
        }
        ranked.sort(Comparator.comparingDouble(Scored::score).reversed());

        int total = ranked.size();
        int keepCount = keep != null
                ? Math.max(1, keep)
                : (int) Math.ceil(total * Math.min(100, Math.max(1, keepPct == null ? 50 : keepPct)) / 100.0);
        // floor so repeated prunes can't cascade the active set down to almost nothing
        keepCount = Math.min(total, Math.max(keepCount, Math.min(total, 5)));

        List<String> kept = ranked.stream().limit(keepCount).map(Scored::name).toList();
        List<String> losers = ranked.stream().skip(keepCount).map(Scored::name).toList();
        String action = hardDelete ? "permanently deleted" : archive ? "archived" : "disabled";
        log.info(
                "Prune: {} strategies with history ranked by avg {} of last {} runs — keeping {}, {} {} + deleting their runs",
                total,
                rankedBy,
                window,
                keepCount,
                losers.size(),
                action);

        int deletedRuns = 0;
        for (String name : losers) {
            tradeRepo.deleteByRunStrategyName(name);
            resultRepo.deleteByRunStrategyName(name);
            deletedRuns += runRepo.deleteByStrategyName(name);
            if (strategies.isStrategy(name)) {
                if (hardDelete) {
                    strategies.deleteStrategy(name);
                } else if (archive) {
                    strategies.setArchived(name, true);
                } else {
                    strategies.setEnabled(name, false);
                }
            }
        }
        for (String name : kept) {
            if (strategies.isStrategy(name)) {
                strategies.setArchived(name, false);
                strategies.setEnabled(name, true);
            }
        }

        log.info("Prune: done — kept {}, {} {}, deleted {} runs", kept.size(), losers.size(), action, deletedRuns);
        return new PruneResultDto(rankedBy, window, total, keepCount, kept, losers, deletedRuns);
    }

    public List<LeaderboardEntryDto> listRuns(String strategy) {
        return listRuns(strategy, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static final Map<String, String> SORT_COLS = Map.ofEntries(
            Map.entry("runId", "id"),
            Map.entry("createdAt", "createdAt"),
            Map.entry("strategy", "strategyName"),
            Map.entry("timeframe", "timeframe"),
            Map.entry("bars", "bars"),
            Map.entry("totalReturnPct", "totalReturnPct"),
            Map.entry("cagrPct", "cagrPct"),
            Map.entry("sharpe", "sharpe"),
            Map.entry("sortino", "sortino"),
            Map.entry("calmar", "calmar"),
            Map.entry("maxDrawdownPct", "maxDrawdownPct"),
            Map.entry("annVolPct", "annVolPct"),
            Map.entry("winRatePct", "winRatePct"),
            Map.entry("profitFactor", "profitFactor"),
            Map.entry("exposurePct", "exposurePct"),
            Map.entry("trades", "trades"));

    /**
     * Run history with optional SQL-side filters and sort.
     * {@code maxDrawdownPct} is entered as a positive magnitude (e.g. 25 → keep runs no worse than -25%).
     */
    public List<LeaderboardEntryDto> listRuns(
            String strategy,
            String symbol,
            Double minReturn,
            Double minCagr,
            Double minSharpe,
            Double minProfitFactor,
            Double minWinRate,
            Double maxDrawdownPct,
            Integer minTrades,
            String sort,
            String dir,
            Integer limit) {
        String col = SORT_COLS.getOrDefault(sort == null ? "" : sort, "createdAt");
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sortOrder = Sort.by(new Sort.Order(direction, col).nullsLast());
        if (!"id".equals(col)) {
            sortOrder = sortOrder.and(Sort.by(Sort.Direction.DESC, "id"));
        }
        int lim = (limit == null || limit <= 0) ? 500 : Math.min(limit, 2000);
        Double ddFloor = maxDrawdownPct == null ? null : -Math.abs(maxDrawdownPct);
        String symbolLike = (symbol == null || symbol.isBlank())
                ? null
                : "%," + symbol.trim().toUpperCase() + ",%";

        List<BacktestRunEntity> runs = runRepo.filter(
                (strategy == null || strategy.isBlank()) ? null : strategy,
                symbolLike,
                minReturn,
                minCagr,
                minSharpe,
                minProfitFactor,
                minWinRate,
                ddFloor,
                minTrades,
                org.springframework.data.domain.PageRequest.of(0, lim, sortOrder));
        log.info(
                "Run history: strategy={} symbol={} sort={} {} -> {} rows",
                strategy == null || strategy.isBlank() ? "*" : strategy,
                symbol == null || symbol.isBlank() ? "*" : symbol.trim().toUpperCase(),
                col,
                direction,
                runs.size());

        List<LeaderboardEntryDto> out = new ArrayList<>();
        for (BacktestRunEntity run : runs) {
            Map<String, Double> metrics = resultRepo
                    .findByRunId(run.getId())
                    .map(res -> new LinkedHashMap<>(json.readDoubleMap(res.getMetricsJson())))
                    .orElseGet(LinkedHashMap::new);
            // the denormalised run columns are the authoritative headline figures
            // (BacktestSummaryBackfill repairs legacy compounding-artifact values there),
            // so let them win over the stored JSON blob.
            metrics.putAll(summaryMetrics(run));
            List<String> resolved =
                    (run.getSymbolsCsv() == null || run.getSymbolsCsv().isBlank())
                            ? List.of()
                            : Arrays.asList(run.getSymbolsCsv().split(","));
            out.add(new LeaderboardEntryDto(
                    run.getId(),
                    run.getStrategyName(),
                    run.getTimeframe() != null ? run.getTimeframe() : Timeframe.NATIVE.name(),
                    run.getBars(),
                    resolved,
                    metrics));
        }
        return out;
    }

    /** Headline metrics from the denormalised run columns (only non-null keys). */
    private static Map<String, Double> summaryMetrics(BacktestRunEntity run) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        if (run.getTotalReturnPct() != null) {
            metrics.put("totalReturnPct", run.getTotalReturnPct());
        }
        if (run.getCagrPct() != null) {
            metrics.put("cagrPct", run.getCagrPct());
        }
        if (run.getSharpe() != null) {
            metrics.put("sharpe", run.getSharpe());
        }
        if (run.getSortino() != null) {
            metrics.put("sortino", run.getSortino());
        }
        if (run.getCalmar() != null) {
            metrics.put("calmar", run.getCalmar());
        }
        if (run.getMaxDrawdownPct() != null) {
            metrics.put("maxDrawdownPct", run.getMaxDrawdownPct());
        }
        if (run.getAnnVolPct() != null) {
            metrics.put("annVolPct", run.getAnnVolPct());
        }
        if (run.getWinRatePct() != null) {
            metrics.put("winRatePct", run.getWinRatePct());
        }
        if (run.getProfitFactor() != null) {
            metrics.put("profitFactor", run.getProfitFactor());
        }
        if (run.getExposurePct() != null) {
            metrics.put("exposurePct", run.getExposurePct());
        }
        if (run.getTrades() != null) {
            metrics.put("trades", (double) run.getTrades());
        }
        return metrics;
    }

    private BacktestResultDto toDto(Long runId, String timeframe, int bars, BacktestOutput output) {
        return new BacktestResultDto(
                runId,
                output.strategy,
                output.symbols,
                output.startDate,
                output.endDate,
                timeframe,
                bars,
                output.metrics,
                datesToStrings(output.dates),
                output.equity,
                output.benchmark,
                output.drawdown,
                output.symbolReturnsPct,
                symbolResultDtos(output.symbolResults),
                output.yearlyReturnsPct,
                output.trades.size());
    }

    /** Copy the headline metrics from a metrics map onto the run row (for sort/filter in SQL). */
    public static void applySummary(BacktestRunEntity run, Map<String, Double> metrics) {
        if (metrics == null) {
            return;
        }
        run.setTotalReturnPct(metrics.get("totalReturnPct"));
        run.setCagrPct(metrics.get("cagrPct"));
        run.setSharpe(metrics.get("sharpe"));
        run.setSortino(metrics.get("sortino"));
        run.setCalmar(metrics.get("calmar"));
        run.setMaxDrawdownPct(metrics.get("maxDrawdownPct"));
        run.setAnnVolPct(metrics.get("annVolPct"));
        run.setWinRatePct(metrics.get("winRatePct"));
        run.setProfitFactor(metrics.get("profitFactor"));
        run.setExposurePct(metrics.get("exposurePct"));
        Double trades = metrics.get("trades");
        run.setTrades(trades == null ? null : (int) Math.round(trades));
    }

    private List<String> datesToStrings(Instant[] dates) {
        List<String> out = new ArrayList<>(dates.length);
        for (Instant date : dates) {
            out.add(date.toString());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> readStrings(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(jsonStr, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
