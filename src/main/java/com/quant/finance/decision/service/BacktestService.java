package com.quant.finance.decision.service;

import com.quant.finance.decision.config.ExecutorConfig;
import com.quant.finance.decision.data.MarketDataService;
import com.quant.finance.decision.entity.BacktestResultEntity;
import com.quant.finance.decision.entity.BacktestRunEntity;
import com.quant.finance.decision.entity.TradeEntity;
import com.quant.finance.decision.engine.BacktestConfig;
import com.quant.finance.decision.engine.BacktestOutput;
import com.quant.finance.decision.engine.Backtester;
import com.quant.finance.decision.engine.BarResampler;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.engine.SymbolResult;
import com.quant.finance.decision.engine.TradeResult;
import com.quant.finance.decision.domain.JobKind;
import com.quant.finance.decision.job.JobProgress;
import com.quant.finance.decision.job.JobProgress.Step;
import com.quant.finance.decision.repository.BacktestResultRepository;
import com.quant.finance.decision.repository.BacktestRunRepository;
import com.quant.finance.decision.repository.TradeRepository;
import com.quant.finance.decision.dto.Dtos.*;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import com.quant.finance.decision.strategy.impl.PairsStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

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

    private static String fmtMetrics(Map<String, Double> m) {
        return String.format("return=%.1f%% sharpe=%.2f maxDD=%.1f%% trades=%.0f",
                m.getOrDefault("totalReturnPct", 0.0), m.getOrDefault("sharpe", 0.0),
                m.getOrDefault("maxDrawdownPct", 0.0), m.getOrDefault("trades", 0.0));
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
    /** Cap on how far back a run-all / batch backtest may reach (heap guard on 1-min data). */
    private final int runAllMaxDays;

    public BacktestService(MarketDataService marketData, StrategyService strategies,
                           UniverseService universe, JsonCodec json,
                           BacktestRunRepository runRepo, BacktestResultRepository resultRepo,
                           TradeRepository tradeRepo,
                           @org.springframework.beans.factory.annotation.Qualifier(
                                   ExecutorConfig.BACKTEST_EXECUTOR) ExecutorService executor,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${decision.backtest.trace-per-symbol:true}") boolean tracePerSymbol,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${decision.backtest.run-all-max-days:400}") int runAllMaxDays) {
        this.backtester = new Backtester(tracePerSymbol);
        this.runAllMaxDays = runAllMaxDays > 0 ? runAllMaxDays : 400;
        this.marketData = marketData;
        this.strategies = strategies;
        this.universe = universe;
        this.json = json;
        this.runRepo = runRepo;
        this.resultRepo = resultRepo;
        this.tradeRepo = tradeRepo;
        this.executor = executor;
    }

    /** Resample targets offered by the backtest form. */
    public List<TimeframeDto> timeframes() {
        List<TimeframeDto> out = new ArrayList<>();
        for (Timeframe t : Timeframe.values())
            out.add(new TimeframeDto(t.name(), t.label, t.isNative()));
        return out;
    }

    /**
     * Resolve the resample frame: an explicit value wins; "AUTO"/blank falls back to the
     * named strategy's recommended frame (or NATIVE when the target isn't a single strategy).
     */
    private Timeframe resolveTimeframe(String strategyName, String reqTimeframe) {
        if (!Timeframe.isAuto(reqTimeframe)) return Timeframe.from(reqTimeframe);
        return strategies.isStrategy(strategyName)
                ? strategies.recommendedTimeframe(strategyName) : Timeframe.NATIVE;
    }

    /** A batch run never executes on 1-min bars: NATIVE is lifted to the floor frame. */
    private static Timeframe batchFrame(Timeframe tf) {
        return tf == null || tf.isNative() ? RUN_ALL_MIN_FRAME : tf;
    }

    private BacktestConfig cfg(BacktestRequest r) { return cfg(r, SINGLE_RUN_MAX_POSITION); }

    private BacktestConfig cfg(BacktestRequest r, double maxPositionSize) {
        BacktestConfig.Builder b = BacktestConfig.builder()
                .capital(r.capital() != null ? r.capital() : 100_000)
                .commissionBps(r.commissionBps() != null ? r.commissionBps() : 1.0)
                .slippageBps(r.slippageBps() != null ? r.slippageBps() : 2.0)
                .allowShort(r.allowShort() == null || r.allowShort())
                .timeframe(resolveTimeframe(r.strategyName(), r.timeframe()));
        if (r.execLag() != null) b.execLag(r.execLag());
        if (r.positionSize() != null) {
            double ps = Math.min(r.positionSize(), maxPositionSize);
            if (ps < r.positionSize())
                log.info("Config[{}]: positionSize {}× capped to {}× (batch run)",
                        r.strategyName() == null ? "ALL" : r.strategyName(), r.positionSize(), ps);
            b.positionSize(ps);
        }
        if (r.riskFreePct() != null) b.riskFreePct(r.riskFreePct());
        if (r.warmupBars() != null) b.warmupBars(r.warmupBars());
        // unset SL/TP on a single named strategy falls back to its own saved default
        // (same "AUTO" convention as timeframe) — a batch/ALL request resolves this per-job instead.
        Double sl = r.stopLossPct();
        if (sl == null && strategies.isStrategy(r.strategyName())) sl = strategies.defaultStopLossPct(r.strategyName());
        if (sl != null) b.stopLossPct(sl);
        Double tp = r.takeProfitPct();
        if (tp == null && strategies.isStrategy(r.strategyName())) tp = strategies.defaultTakeProfitPct(r.strategyName());
        if (tp != null) b.takeProfitPct(tp);
        BacktestConfig cfg = b.build();
        log.info("Config[{}]: tf={} tfReq={} capital={} comm={}bps slip={}bps posSize={} execLag={} SL={}% TP={}% warmup={} rf={}% allowShort={}",
                r.strategyName(), cfg.timeframe, r.timeframe() == null ? "AUTO" : r.timeframe(), cfg.capital,
                cfg.commissionBps, cfg.slippageBps, cfg.positionSize, cfg.execLag, cfg.stopLossPct,
                cfg.takeProfitPct, cfg.warmupBars, cfg.riskFreePct, cfg.allowShort);
        return cfg;
    }

    private List<String> resolveSymbols(List<String> requested) {
        if (requested != null && !requested.isEmpty()) {
            List<String> syms = requested.stream().map(String::toUpperCase).toList();
            log.info("Symbols: {} requested {}", syms.size(), preview(syms));
            return syms;
        }
        List<String> syms = universe.get();
        log.info("Symbols: {} from universe {}", syms.size(), preview(syms));
        return syms;
    }

    private static double orZero(Double v) { return v == null ? 0 : v; }

    private static String preview(List<String> l) {
        return l.size() <= 12 ? l.toString() : l.subList(0, 12) + " …+" + (l.size() - 12);
    }

    private List<BarSeries> load(List<String> symbols, LocalDate start, LocalDate end, Timeframe tf) {
        return load(symbols, start, end, tf, new JobProgress(JobKind.RUN));
    }

    private List<BarSeries> load(List<String> symbols, LocalDate start, LocalDate end, Timeframe tf,
                                 JobProgress progress) {
        long t0 = System.currentTimeMillis();
        log.info("Load bars: {} symbol(s) {}..{} @ {}", symbols.size(), start, end, tf);
        List<BarSeries> out = new ArrayList<>();
        int miss = 0;
        long bars = 0;
        for (String s : symbols) {
            progress.checkCancelled();
            BarSeries b = marketData.getBars(s, start, end);
            if (b.size() > 0) { BarSeries rs = BarResampler.resample(b, tf); out.add(rs); bars += rs.size(); }
            else miss++;
            progress.symbolLoaded(s, b.size() > 0);
        }
        log.info("Load bars: {} series ready, {} bars total{} in {} ms", out.size(), bars,
                miss > 0 ? " (" + miss + " symbol(s) had no data)" : "", System.currentTimeMillis() - t0);
        if (out.isEmpty()) {
            log.warn("Load bars: no market data for {} @ {} {}..{}", preview(symbols), tf, start, end);
            throw new IllegalStateException("No market data for requested symbols/range");
        }
        return out;
    }

    /**
     * Loads native bars once per symbol and resamples each into every requested frame, holding one symbol's native series at a time
     * (1-min native is ~1.9M bars in a batch, so this keeps the working set an order of magnitude smaller).
     */
    private Map<Timeframe, List<BarSeries>> loadResampled(List<String> symbols, LocalDate start,
                                                          LocalDate end, Set<Timeframe> frames,
                                                          JobProgress progress) {
        long t0 = System.currentTimeMillis();
        Map<Timeframe, List<BarSeries>> out = new LinkedHashMap<>();
        for (Timeframe tf : frames) out.put(tf, new ArrayList<>());
        long bars = 0;
        int miss = 0;
        for (String s : symbols) {
            progress.checkCancelled();
            BarSeries nativeB = marketData.getBars(s, start, end);
            if (nativeB.size() == 0) { miss++; progress.symbolLoaded(s, false); continue; }
            for (Timeframe tf : frames) {
                BarSeries rs = BarResampler.resample(nativeB, tf);
                out.get(tf).add(rs);
                bars += rs.size();
            }
            progress.symbolLoaded(s, true);
        }
        log.info("Load bars: {} symbol(s) -> {} frame(s) {}, {} resampled bars total{} in {} ms",
                symbols.size(), frames.size(), frames, bars,
                miss > 0 ? " (" + miss + " symbol(s) had no data)" : "", System.currentTimeMillis() - t0);
        if (out.values().stream().allMatch(List::isEmpty)) {
            log.warn("Load bars: no market data for {} {}..{}", preview(symbols), start, end);
            throw new IllegalStateException("No market data for requested symbols/range");
        }
        return out;
    }

    // ---- shared entry points for the optimizer / ensemble services --------

    /** Requested symbols upper-cased, or the saved universe when none are given. */
    public List<String> universeOr(List<String> requested) { return resolveSymbols(requested); }

    /** Load + resample bars once so a sweep can reuse them across many runs. */
    public List<BarSeries> loadData(List<String> symbols, LocalDate start, LocalDate end, Timeframe tf) {
        return load(symbols, start, end, tf);
    }

    /** As {@link #loadData(List, LocalDate, LocalDate, Timeframe)}, reporting each symbol to {@code progress}. */
    public List<BarSeries> loadData(List<String> symbols, LocalDate start, LocalDate end, Timeframe tf,
                                    JobProgress progress) {
        return load(symbols, start, end, tf, progress);
    }

    public BacktestConfig configOf(BacktestRequest req) { return cfg(req); }

    /** Shared backtest thread pool — for the optimiser / ensemble to fan out their own work. */
    public ExecutorService executor() { return executor; }

    /** Run one portfolio backtest against already-loaded data and return only its metrics (no persistence). */
    public Map<String, Double> evaluate(TradingStrategy strat, Map<String, Double> params,
                                        List<BarSeries> data, BacktestConfig cfg) {
        BacktestOutput o = backtester.runPortfolio(data, strat,
                params == null || params.isEmpty() ? null : params, cfg, null, false);
        return o.metrics;
    }

    /** Full portfolio output against already-loaded data (no persistence) — for the ensemble blend. */
    public BacktestOutput runOnce(TradingStrategy strat, Map<String, Double> params,
                                  List<BarSeries> data, BacktestConfig cfg) {
        return backtester.runPortfolio(data, strat, params == null || params.isEmpty() ? null : params, cfg, null, false);
    }

    @Transactional
    public BacktestResultDto run(BacktestRequest req) {
        return run(req, new JobProgress(JobKind.RUN));
    }

    @Transactional
    public BacktestResultDto run(BacktestRequest req, JobProgress progress) {
        progress.begin(Step.PREPARE, 1, "Resolving symbols and settings");
        List<String> symbols = resolveSymbols(req.symbols());
        BacktestConfig cfg = cfg(req);
        TradingStrategy strat = strategies.getStrategy(req.strategyName());
        Map<String, Double> params = strategies.getParams(req.strategyName());
        progress.planSymbols(symbols);
        progress.planStrategies(List.of(req.strategyName()));
        progress.complete(Step.PREPARE);
        long t0 = System.currentTimeMillis();
        log.info("Backtest: '{}' on {} symbol(s) {}..{} @ {}", req.strategyName(), symbols.size(),
                req.start(), req.end(), cfg.timeframe);
        progress.begin(Step.LOAD, symbols.size(), "Loading market data");
        List<BarSeries> data = load(symbols, req.start(), req.end(), cfg.timeframe, progress);
        progress.complete(Step.LOAD);
        progress.checkCancelled();
        log.info("Backtest: '{}' computing portfolio over {} series (symbols in parallel)…", req.strategyName(), data.size());
        progress.beginCompute(data.size(), 1, "Computing " + data.size() + " symbol(s)");
        progress.strategyStarted(req.strategyName());
        BacktestOutput o = backtester.runPortfolio(data, strat, params.isEmpty() ? null : params, cfg, executor,
                true, progress::symbolComputed);
        progress.strategyFinished(req.strategyName(), true);
        progress.complete(Step.COMPUTE);
        progress.checkCancelled();
        progress.begin(Step.SAVE, 1, "Saving the run, its results and trades");
        BacktestResultDto dto = persist(o, symbols, cfg, req.start(), req.end());
        progress.complete(Step.SAVE);
        log.info("Backtest: '{}' done in {} ms — run #{} {}", req.strategyName(),
                System.currentTimeMillis() - t0, dto.runId(), fmtMetrics(o.metrics));
        return dto;
    }

    @Transactional
    public List<LeaderboardEntryDto> runAll(BacktestRequest req) {
        return runAll(req, new JobProgress(JobKind.RUN_ALL));
    }

    @Transactional
    public List<LeaderboardEntryDto> runAll(BacktestRequest req, JobProgress progress) {
        progress.begin(Step.PREPARE, 1, "Resolving symbols, strategies and settings");
        List<String> symbols = resolveSymbols(req.symbols());

        // cap the look-back window — hundreds of strategies over years of 1-min bars is what OOMs the box
        LocalDate start = req.start(), end = req.end();
        if (start != null && end != null && start.isBefore(end.minusDays(runAllMaxDays))) {
            LocalDate capped = end.minusDays(runAllMaxDays);
            log.info("Backtest run-all: window {}..{} exceeds the {}-day batch cap — starting from {}",
                    start, end, runAllMaxDays, capped);
            start = capped;
        }

        BacktestConfig cfg = cfg(req, MULTI_RUN_MAX_POSITION);
        // AUTO (or an explicit "Native") on a batch run must never execute on 1-min bars:
        // AUTO fans each strategy out on its own recommended frame; an explicit Native is
        // lifted to the batch floor frame.
        boolean perStrategyTf = Boolean.TRUE.equals(req.perStrategyTimeframe())
                || Timeframe.isAuto(req.timeframe());
        Timeframe sharedFrame = cfg.timeframe.isNative() ? RUN_ALL_MIN_FRAME : cfg.timeframe;

        boolean includeDisabled = Boolean.TRUE.equals(req.includeDisabled());
        List<String> picked = req.strategyNames();
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
        if (enabled.isEmpty())
            throw new IllegalStateException(picked != null && !picked.isEmpty()
                    ? "None of the picked strategies are runnable (unknown or archived)."
                    : includeDisabled
                        ? "No strategies to run — every strategy is archived."
                        : "No enabled strategies — enable some on the Strategies tab, or use 'include disabled'.");

        // which resample frames does this batch actually need? (a per-strategy frame that
        // resolves to Native is lifted to the batch floor — no batch runs on 1-min bars)
        Set<Timeframe> frames = new LinkedHashSet<>();
        if (perStrategyTf) for (String name : enabled.keySet()) frames.add(batchFrame(strategies.recommendedTimeframe(name)));
        else frames.add(sharedFrame);

        log.info("Backtest run-all: {} {} strategies on {} symbol(s) {}..{} ({})",
                enabled.size(), scope, symbols.size(), start, end,
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
        boolean autoSL = req.stopLossPct() == null;
        boolean autoTP = req.takeProfitPct() == null;

        // --- prep one job per strategy (single-threaded: resolves params / timeframe / data, no compute) ---
        record Job(String name, TradingStrategy strat, Map<String, Double> params,
                   BacktestConfig cfg, List<BarSeries> data) {}
        List<Job> jobs = new ArrayList<>();
        for (Map.Entry<String, TradingStrategy> e : enabled.entrySet()) {
            Map<String, Double> params = strategies.getParams(e.getKey());
            Timeframe tf = perStrategyTf ? batchFrame(strategies.recommendedTimeframe(e.getKey())) : sharedFrame;
            BacktestConfig runCfg = cfg.timeframe == tf ? cfg : cfg.withTimeframe(tf);
            if (autoSL || autoTP) {
                double sl = autoSL ? orZero(strategies.defaultStopLossPct(e.getKey())) : runCfg.stopLossPct;
                double tp = autoTP ? orZero(strategies.defaultTakeProfitPct(e.getKey())) : runCfg.takeProfitPct;
                runCfg = runCfg.withRisk(sl, tp);
            }
            jobs.add(new Job(e.getKey(), e.getValue(), params.isEmpty() ? null : params, runCfg, tfData.get(tf)));
        }
        log.info("Backtest run-all: {} jobs prepared ({} distinct timeframe(s)) — submitting to pool",
                jobs.size(), frames.size());

        // --- fan the CPU-bound backtests out across the pool (no DB in the tasks) ---
        // one strategy failing (bad data, indicator NaN, …) must not abort the whole batch
        int total = jobs.size();
        int units = 0;
        for (Job j : jobs) units += j.data().size();     // one unit per (strategy, loaded symbol)
        progress.beginCompute(units, total, total + " strategies × " + (units / Math.max(1, total)) + " symbol(s)");
        List<CompletableFuture<BacktestOutput>> futures = new ArrayList<>(total);
        for (Job j : jobs) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (progress.isCancelled()) return null;   // stopped: skip work that has not started yet
                progress.strategyStarted(j.name());
                boolean ok = false;
                try {
                    long t0 = System.currentTimeMillis();
                    BacktestOutput o = backtester.runPortfolio(j.data(), j.strat(), j.params(), j.cfg(),
                            null, true, progress::symbolComputed);
                    log.info("Backtest run-all: '{}' @ {} computed in {} ms — {}",
                            j.name(), j.cfg().timeframe, System.currentTimeMillis() - t0, fmtMetrics(o.metrics));
                    ok = true;
                    return o;
                } catch (RuntimeException ex) {
                    log.warn("Backtest run-all: '{}' failed — {}: {}", j.name(),
                            ex.getClass().getSimpleName(), ex.getMessage());
                    return null;
                } finally {
                    progress.strategyFinished(j.name(), ok);
                }
            }, executor));
        }

        // --- persist sequentially on this transaction thread ---
        progress.begin(Step.SAVE, total, "Waiting for the first strategy");
        List<LeaderboardEntryDto> board = new ArrayList<>(total);
        int failed = 0;
        for (int i = 0; i < total; i++) {
            progress.checkCancelled();
            Job j = jobs.get(i);
            BacktestOutput o = futures.get(i).join();
            futures.set(i, null);   // release the output once we've persisted / skipped it
            progress.checkCancelled();
            if (o == null) {
                failed++;
                progress.advance(Step.SAVE, j.name() + " failed (" + (i + 1) + "/" + total + ")");
                continue;
            }
            BacktestResultDto dto = persist(o, symbols, j.cfg(), start, end);
            board.add(new LeaderboardEntryDto(dto.runId(), dto.strategy(), dto.timeframe(), dto.bars(),
                    dto.symbols(), dto.metrics()));
            progress.advance(Step.SAVE, "Saved " + j.name() + " (" + (i + 1) + "/" + total + ")");
        }
        progress.complete(Step.COMPUTE);
        progress.complete(Step.SAVE);
        board.sort((a, b) -> Double.compare(
                b.metrics().getOrDefault("sharpe", 0.0), a.metrics().getOrDefault("sharpe", 0.0)));
        log.info("Backtest run-all: {} of {} strategies done in {} ms (parallel){}", board.size(), total,
                System.currentTimeMillis() - batchStart, failed > 0 ? " — " + failed + " failed" : "");
        return board;
    }

    @Transactional
    public BacktestResultDto runPairs(PairsRequest req) {
        return runPairs(req, new JobProgress(JobKind.PAIRS));
    }

    @Transactional
    public BacktestResultDto runPairs(PairsRequest req, JobProgress progress) {
        progress.begin(Step.PREPARE, 1, "Preparing the pair");
        BacktestConfig.Builder b = BacktestConfig.builder()
                .capital(req.capital() != null ? req.capital() : 100_000)
                .commissionBps(req.commissionBps() != null ? req.commissionBps() : 1.0)
                .slippageBps(req.slippageBps() != null ? req.slippageBps() : 2.0)
                .allowShort(true)
                .timeframe(Timeframe.from(req.timeframe()));
        if (req.positionSize() != null) b.positionSize(req.positionSize());
        if (req.stopLossPct() != null) b.stopLossPct(req.stopLossPct());
        if (req.takeProfitPct() != null) b.takeProfitPct(req.takeProfitPct());
        BacktestConfig cfg = b.build();
        String symA = req.symbolA().toUpperCase(), symB = req.symbolB().toUpperCase();
        progress.planSymbols(List.of(symA, symB));
        progress.complete(Step.PREPARE);

        long t0 = System.currentTimeMillis();
        log.info("Backtest: pairs {}/{} {}..{} @ {}", req.symbolA(), req.symbolB(),
                req.start(), req.end(), cfg.timeframe);
        progress.begin(Step.LOAD, 2, "Loading market data");
        progress.checkCancelled();
        BarSeries rawA = marketData.getBars(symA, req.start(), req.end());
        progress.symbolLoaded(symA, rawA.size() > 0);
        progress.checkCancelled();
        BarSeries rawB = marketData.getBars(symB, req.start(), req.end());
        progress.symbolLoaded(symB, rawB.size() > 0);
        BarSeries a = BarResampler.resample(rawA, cfg.timeframe);
        BarSeries bs = BarResampler.resample(rawB, cfg.timeframe);
        progress.complete(Step.LOAD);
        progress.checkCancelled();
        PairsStrategy strat = new PairsStrategy(
                req.window() != null ? req.window() : 60,
                req.entry() != null ? req.entry() : 2.0,
                req.exit() != null ? req.exit() : 0.5);
        progress.beginCompute(1, 0, "Backtesting the pair");
        BacktestOutput o = backtester.runPairs(a, bs, strat, cfg);
        progress.complete(Step.COMPUTE);
        progress.checkCancelled();
        progress.begin(Step.SAVE, 1, "Saving the run, its results and trades");
        BacktestResultDto dto = persist(o, List.of(symA, symB), cfg, req.start(), req.end());
        progress.complete(Step.SAVE);
        log.info("Backtest: pairs {}/{} done in {} ms — run #{} {}", req.symbolA(), req.symbolB(),
                System.currentTimeMillis() - t0, dto.runId(), fmtMetrics(o.metrics));
        return dto;
    }

    private BacktestResultDto persist(BacktestOutput o, List<String> symbols, BacktestConfig cfg,
                                      LocalDate start, LocalDate end) {
        BacktestRunEntity run = new BacktestRunEntity();
        run.setStrategyName(o.strategy);
        run.setSymbolsCsv(String.join(",", symbols));
        run.setStartDate(o.startDate);
        run.setEndDate(o.endDate);
        run.setCapital(cfg.capital);
        run.setCommissionBps(cfg.commissionBps);
        run.setSlippageBps(cfg.slippageBps);
        run.setAllowShort(cfg.allowShort);
        run.setTimeframe(cfg.timeframe.name());
        run.setExecLag(cfg.execLag);
        run.setPositionSize(cfg.positionSize);
        run.setRiskFreePct(cfg.riskFreePct);
        run.setWarmupBars(cfg.warmupBars);
        run.setStopLossPct(cfg.stopLossPct);
        run.setTakeProfitPct(cfg.takeProfitPct);
        run.setBars(o.dates.length);
        applySummary(run, o.metrics);
        run.setStatus("COMPLETED");
        run = runRepo.save(run);

        BacktestResultEntity result = new BacktestResultEntity();
        result.setRun(run);
        result.setMetricsJson(json.write(o.metrics));
        result.setDatesJson(json.write(datesToStrings(o.dates)));
        result.setEquityJson(json.write(o.equity));
        result.setBenchmarkJson(json.write(o.benchmark));
        result.setDrawdownJson(json.write(o.drawdown));
        result.setSymbolReturnsJson(json.write(o.symbolReturnsPct));
        result.setSymbolDetailsJson(json.write(new SymbolDetails(o.yearlyReturnsPct, o.symbolResults)));
        resultRepo.save(result);

        List<TradeEntity> trades = new ArrayList<>();
        for (TradeResult t : o.trades) {
            TradeEntity te = new TradeEntity();
            te.setRun(run);
            te.setSymbol(t.symbol);
            te.setSide(t.side);
            te.setEntryDate(t.entryDate);
            te.setExitDate(t.exitDate);
            te.setEntryPx(t.entryPx);
            te.setExitPx(t.exitPx);
            te.setBars(t.bars);
            te.setReturnPct(t.returnPct);
            te.setGrossReturn(t.grossReturn);
            te.setCost(t.cost);
            te.setNetReturn(t.netReturn);
            te.setExposure(t.exposure);
            te.setStillOpen(t.open);
            trades.add(te);
        }
        tradeRepo.saveAll(trades);
        log.info("Persisted run #{}: '{}' @ {} — {} symbols, {} bars, {} trades", run.getId(), o.strategy,
                cfg.timeframe.name(), symbols.size(), o.dates.length, trades.size());

        return toDto(run.getId(), cfg.timeframe.name(), o.dates.length, o);
    }

    public BacktestResultDto getRun(Long runId) {
        log.info("Backtest: loading run #{}", runId);
        BacktestRunEntity run = runRepo.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("No run " + runId));
        BacktestResultEntity res = resultRepo.findByRunId(runId)
                .orElseThrow(() -> new NoSuchElementException("No result for run " + runId));
        // runs saved before per-symbol results existed have no details document: empty, not an error
        SymbolDetails details = json.read(res.getSymbolDetailsJson(), SymbolDetails.class);
        List<String> symbols = run.getSymbolsCsv() == null ? List.of()
                : Arrays.asList(run.getSymbolsCsv().split(","));
        List<String> dates = readStrings(res.getDatesJson());
        String tf = run.getTimeframe() != null ? run.getTimeframe() : Timeframe.NATIVE.name();
        int bars = run.getBars() != null ? run.getBars() : dates.size();
        return new BacktestResultDto(runId, run.getStrategyName(), symbols,
                run.getStartDate(), run.getEndDate(), tf, bars, json.readMetrics(res.getMetricsJson()),
                dates, json.readDoubles(res.getEquityJson()),
                json.readDoubles(res.getBenchmarkJson()), json.readDoubles(res.getDrawdownJson()),
                json.readMetrics(res.getSymbolReturnsJson()),
                details == null ? List.of() : symbolResultDtos(details.symbols()),
                details == null || details.portfolioYearlyReturnsPct() == null ? Map.of() : details.portfolioYearlyReturnsPct(),
                tradeRepo.countByRunId(runId));
    }

    /**
     * One page of a run's trades, optionally for one symbol / side, with totals over everything
     * that matches. Loaded whole and paged in memory (see {@link TradeDetails}).
     */
    public TradePageDto getTrades(Long runId, String symbol, String side, String sort, String dir,
                                  Integer page, Integer size) {
        BacktestRunEntity run = runRepo.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("No run " + runId));
        TradeDetails.RunCosts costs = new TradeDetails.RunCosts(run.getCapital(), run.getCommissionBps(),
                run.getSlippageBps(), run.getPositionSize() != null ? run.getPositionSize() : 1.0);
        List<TradeDetailDto> all = tradeRepo.findByRunId(runId).stream()
                .map(t -> TradeDetails.toDto(t, costs)).toList();
        return TradeDetails.page(all, symbol, side, sort, dir,
                page == null ? 0 : page, size == null ? TradeDetails.DEFAULT_PAGE_SIZE : size);
    }

    /** What {@code backtest_result.symbol_details_json} holds. */
    public record SymbolDetails(Map<String, Double> portfolioYearlyReturnsPct, Map<String, SymbolResult> symbols) {}

    private static List<SymbolResultDto> symbolResultDtos(Map<String, SymbolResult> results) {
        if (results == null) return List.of();
        List<SymbolResultDto> out = new ArrayList<>(results.size());
        results.forEach((sym, r) -> out.add(new SymbolResultDto(sym, r.metrics(), r.yearlyReturnsPct())));
        return out;
    }

    @Transactional
    public void deleteRun(Long runId) {
        if (!runRepo.existsById(runId)) throw new NoSuchElementException("No run " + runId);
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
            double sum = 0; int c = 0;
            for (BacktestRunEntity r : runs) {
                if (c >= window) break;
                Double v = bySharpe ? r.getSharpe() : r.getTotalReturnPct();
                if (v != null && !v.isNaN()) { sum += v; c++; }
            }
            ranked.add(new Scored(name, c > 0 ? sum / c : Double.NEGATIVE_INFINITY));
        }
        ranked.sort(Comparator.comparingDouble(Scored::score).reversed());

        int total = ranked.size();
        int k = keep != null ? Math.max(1, keep)
                : (int) Math.ceil(total * Math.min(100, Math.max(1, keepPct == null ? 50 : keepPct)) / 100.0);
        // floor so repeated prunes can't cascade the active set down to almost nothing
        k = Math.min(total, Math.max(k, Math.min(total, 5)));

        List<String> kept = ranked.stream().limit(k).map(Scored::name).toList();
        List<String> losers = ranked.stream().skip(k).map(Scored::name).toList();
        String action = hardDelete ? "permanently deleted" : archive ? "archived" : "disabled";
        log.info("Prune: {} strategies with history ranked by avg {} of last {} runs — keeping {}, {} {} + deleting their runs",
                total, rankedBy, window, k, losers.size(), action);

        int deletedRuns = 0;
        for (String name : losers) {
            tradeRepo.deleteByRunStrategyName(name);
            resultRepo.deleteByRunStrategyName(name);
            deletedRuns += runRepo.deleteByStrategyName(name);
            if (strategies.isStrategy(name)) {
                if (hardDelete) strategies.deleteStrategy(name);
                else if (archive) strategies.setArchived(name, true);
                else strategies.setEnabled(name, false);
            }
        }
        for (String name : kept)
            if (strategies.isStrategy(name)) {
                strategies.setArchived(name, false);
                strategies.setEnabled(name, true);
            }

        log.info("Prune: done — kept {}, {} {}, deleted {} runs", kept.size(), losers.size(), action, deletedRuns);
        return new PruneResultDto(rankedBy, window, total, k, kept, losers, deletedRuns);
    }

    public List<LeaderboardEntryDto> listRuns(String strategy) {
        return listRuns(strategy, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static final Map<String, String> SORT_COLS = Map.ofEntries(
            Map.entry("runId", "id"), Map.entry("createdAt", "createdAt"), Map.entry("strategy", "strategyName"),
            Map.entry("timeframe", "timeframe"), Map.entry("bars", "bars"),
            Map.entry("totalReturnPct", "totalReturnPct"), Map.entry("cagrPct", "cagrPct"),
            Map.entry("sharpe", "sharpe"), Map.entry("sortino", "sortino"), Map.entry("calmar", "calmar"),
            Map.entry("maxDrawdownPct", "maxDrawdownPct"), Map.entry("annVolPct", "annVolPct"),
            Map.entry("winRatePct", "winRatePct"), Map.entry("profitFactor", "profitFactor"),
            Map.entry("exposurePct", "exposurePct"), Map.entry("trades", "trades"));

    /**
     * Run history with optional SQL-side filters and sort.
     * {@code maxDrawdownPct} is entered as a positive magnitude (e.g. 25 → keep runs no worse than -25%).
     */
    public List<LeaderboardEntryDto> listRuns(String strategy, String symbol, Double minReturn, Double minCagr,
                                              Double minSharpe, Double minProfitFactor, Double minWinRate,
                                              Double maxDrawdownPct, Integer minTrades,
                                              String sort, String dir, Integer limit) {
        String col = SORT_COLS.getOrDefault(sort == null ? "" : sort, "createdAt");
        org.springframework.data.domain.Sort.Direction d =
                "asc".equalsIgnoreCase(dir) ? org.springframework.data.domain.Sort.Direction.ASC
                        : org.springframework.data.domain.Sort.Direction.DESC;
        org.springframework.data.domain.Sort s =
                org.springframework.data.domain.Sort.by(new org.springframework.data.domain.Sort.Order(d, col).nullsLast());
        if (!"id".equals(col))
            s = s.and(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id"));
        int lim = (limit == null || limit <= 0) ? 500 : Math.min(limit, 2000);
        Double ddFloor = maxDrawdownPct == null ? null : -Math.abs(maxDrawdownPct);
        String symbolLike = (symbol == null || symbol.isBlank())
                ? null : "%," + symbol.trim().toUpperCase() + ",%";

        List<BacktestRunEntity> runs = runRepo.filter(
                (strategy == null || strategy.isBlank()) ? null : strategy, symbolLike,
                minReturn, minCagr, minSharpe, minProfitFactor, minWinRate, ddFloor, minTrades,
                org.springframework.data.domain.PageRequest.of(0, lim, s));
        log.info("Run history: strategy={} symbol={} sort={} {} -> {} rows",
                strategy == null || strategy.isBlank() ? "*" : strategy,
                symbol == null || symbol.isBlank() ? "*" : symbol.trim().toUpperCase(),
                col, d, runs.size());

        List<LeaderboardEntryDto> out = new ArrayList<>();
        for (BacktestRunEntity r : runs) {
            Map<String, Double> metrics = resultRepo.findByRunId(r.getId())
                    .map(res -> new LinkedHashMap<>(json.readMetrics(res.getMetricsJson())))
                    .orElseGet(LinkedHashMap::new);
            // the denormalised run columns are the authoritative headline figures
            // (BacktestSummaryBackfill repairs legacy compounding-artifact values there),
            // so let them win over the stored JSON blob.
            metrics.putAll(summaryMetrics(r));
            List<String> syms = (r.getSymbolsCsv() == null || r.getSymbolsCsv().isBlank())
                    ? List.of() : Arrays.asList(r.getSymbolsCsv().split(","));
            out.add(new LeaderboardEntryDto(r.getId(), r.getStrategyName(),
                    r.getTimeframe() != null ? r.getTimeframe() : Timeframe.NATIVE.name(),
                    r.getBars(), syms, metrics));
        }
        return out;
    }

    /** Headline metrics from the denormalised run columns (only non-null keys). */
    private static Map<String, Double> summaryMetrics(BacktestRunEntity r) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (r.getTotalReturnPct() != null) m.put("totalReturnPct", r.getTotalReturnPct());
        if (r.getCagrPct() != null) m.put("cagrPct", r.getCagrPct());
        if (r.getSharpe() != null) m.put("sharpe", r.getSharpe());
        if (r.getSortino() != null) m.put("sortino", r.getSortino());
        if (r.getCalmar() != null) m.put("calmar", r.getCalmar());
        if (r.getMaxDrawdownPct() != null) m.put("maxDrawdownPct", r.getMaxDrawdownPct());
        if (r.getAnnVolPct() != null) m.put("annVolPct", r.getAnnVolPct());
        if (r.getWinRatePct() != null) m.put("winRatePct", r.getWinRatePct());
        if (r.getProfitFactor() != null) m.put("profitFactor", r.getProfitFactor());
        if (r.getExposurePct() != null) m.put("exposurePct", r.getExposurePct());
        if (r.getTrades() != null) m.put("trades", (double) r.getTrades());
        return m;
    }

    private BacktestResultDto toDto(Long runId, String timeframe, int bars, BacktestOutput o) {
        return new BacktestResultDto(runId, o.strategy, o.symbols, o.startDate, o.endDate,
                timeframe, bars, o.metrics, datesToStrings(o.dates), o.equity, o.benchmark, o.drawdown,
                o.symbolReturnsPct, symbolResultDtos(o.symbolResults), o.yearlyReturnsPct, o.trades.size());
    }

    /** Copy the headline metrics from a metrics map onto the run row (for sort/filter in SQL). */
    public static void applySummary(BacktestRunEntity run, Map<String, Double> m) {
        if (m == null) return;
        run.setTotalReturnPct(m.get("totalReturnPct"));
        run.setCagrPct(m.get("cagrPct"));
        run.setSharpe(m.get("sharpe"));
        run.setSortino(m.get("sortino"));
        run.setCalmar(m.get("calmar"));
        run.setMaxDrawdownPct(m.get("maxDrawdownPct"));
        run.setAnnVolPct(m.get("annVolPct"));
        run.setWinRatePct(m.get("winRatePct"));
        run.setProfitFactor(m.get("profitFactor"));
        run.setExposurePct(m.get("exposurePct"));
        Double t = m.get("trades");
        run.setTrades(t == null ? null : (int) Math.round(t));
    }

    private List<String> datesToStrings(Instant[] dates) {
        List<String> out = new ArrayList<>(dates.length);
        for (Instant d : dates) out.add(d.toString());
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> readStrings(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(jsonStr, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
