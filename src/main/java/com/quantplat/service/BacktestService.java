package com.quantplat.service;

import com.quantplat.data.MarketDataService;
import com.quantplat.domain.*;
import com.quantplat.dto.Dtos.*;
import com.quantplat.engine.*;
import com.quantplat.repository.*;
import com.quantplat.strategy.BarSeries;
import com.quantplat.strategy.TradingStrategy;
import com.quantplat.strategy.impl.PairsStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

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
    private final Backtester backtester = new Backtester();

    public BacktestService(MarketDataService marketData, StrategyService strategies,
                           UniverseService universe, JsonCodec json,
                           BacktestRunRepository runRepo, BacktestResultRepository resultRepo,
                           TradeRepository tradeRepo) {
        this.marketData = marketData;
        this.strategies = strategies;
        this.universe = universe;
        this.json = json;
        this.runRepo = runRepo;
        this.resultRepo = resultRepo;
        this.tradeRepo = tradeRepo;
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

    private BacktestConfig cfg(BacktestRequest r) {
        BacktestConfig.Builder b = BacktestConfig.builder()
                .capital(r.capital() != null ? r.capital() : 100_000)
                .commissionBps(r.commissionBps() != null ? r.commissionBps() : 1.0)
                .slippageBps(r.slippageBps() != null ? r.slippageBps() : 2.0)
                .allowShort(r.allowShort() == null || r.allowShort())
                .timeframe(resolveTimeframe(r.strategyName(), r.timeframe()));
        if (r.execLag() != null) b.execLag(r.execLag());
        if (r.positionSize() != null) b.positionSize(r.positionSize());
        if (r.riskFreePct() != null) b.riskFreePct(r.riskFreePct());
        if (r.warmupBars() != null) b.warmupBars(r.warmupBars());
        if (r.stopLossPct() != null) b.stopLossPct(r.stopLossPct());
        if (r.takeProfitPct() != null) b.takeProfitPct(r.takeProfitPct());
        return b.build();
    }

    private List<String> resolveSymbols(List<String> requested) {
        if (requested != null && !requested.isEmpty())
            return requested.stream().map(String::toUpperCase).toList();
        return universe.get();
    }

    private List<BarSeries> load(List<String> symbols, LocalDate start, LocalDate end, Timeframe tf) {
        List<BarSeries> out = new ArrayList<>();
        for (String s : symbols) {
            BarSeries b = marketData.getBars(s, start, end);
            if (b.size() > 0) out.add(BarResampler.resample(b, tf));
        }
        if (out.isEmpty()) throw new IllegalStateException("No market data for requested symbols/range");
        return out;
    }

    // ---- shared entry points for the optimizer / ensemble services --------

    /** Requested symbols upper-cased, or the saved universe when none are given. */
    public List<String> universeOr(List<String> requested) { return resolveSymbols(requested); }

    /** Load + resample bars once so a sweep can reuse them across many runs. */
    public List<BarSeries> loadData(List<String> symbols, LocalDate start, LocalDate end, Timeframe tf) {
        return load(symbols, start, end, tf);
    }

    public BacktestConfig configOf(BacktestRequest req) { return cfg(req); }

    /** Run one portfolio backtest against already-loaded data and return only its metrics (no persistence). */
    public Map<String, Double> evaluate(TradingStrategy strat, Map<String, Double> params,
                                        List<BarSeries> data, BacktestConfig cfg) {
        BacktestOutput o = backtester.runPortfolio(data, strat, params == null || params.isEmpty() ? null : params, cfg);
        return o.metrics;
    }

    /** Full portfolio output against already-loaded data (no persistence) — for the ensemble blend. */
    public BacktestOutput runOnce(TradingStrategy strat, Map<String, Double> params,
                                  List<BarSeries> data, BacktestConfig cfg) {
        return backtester.runPortfolio(data, strat, params == null || params.isEmpty() ? null : params, cfg);
    }

    @Transactional
    public BacktestResultDto run(BacktestRequest req) {
        List<String> symbols = resolveSymbols(req.symbols());
        BacktestConfig cfg = cfg(req);
        TradingStrategy strat = strategies.getStrategy(req.strategyName());
        Map<String, Double> params = strategies.getParams(req.strategyName());
        long t0 = System.currentTimeMillis();
        log.info("Backtest: '{}' on {} symbol(s) {}..{} @ {}", req.strategyName(), symbols.size(),
                req.start(), req.end(), cfg.timeframe);
        List<BarSeries> data = load(symbols, req.start(), req.end(), cfg.timeframe);
        BacktestOutput o = backtester.runPortfolio(data, strat, params.isEmpty() ? null : params, cfg);
        BacktestResultDto dto = persist(o, symbols, cfg, req.start(), req.end());
        log.info("Backtest: '{}' done in {} ms — run #{} {}", req.strategyName(),
                System.currentTimeMillis() - t0, dto.runId(), fmtMetrics(o.metrics));
        return dto;
    }

    @Transactional
    public List<LeaderboardEntryDto> runAll(BacktestRequest req) {
        List<String> symbols = resolveSymbols(req.symbols());
        BacktestConfig cfg = cfg(req);
        boolean perStrategyTf = Boolean.TRUE.equals(req.perStrategyTimeframe());

        // When each strategy runs on its own recommended frame, load native bars once and
        // resample per strategy; otherwise resample once to the shared frame.
        List<BarSeries> nativeData = perStrategyTf ? load(symbols, req.start(), req.end(), Timeframe.NATIVE) : null;
        List<BarSeries> sharedData = perStrategyTf ? null : load(symbols, req.start(), req.end(), cfg.timeframe);

        Map<String, TradingStrategy> enabled = strategies.getEnabledStrategies();
        long batchStart = System.currentTimeMillis();
        log.info("Backtest run-all: {} enabled strategies on {} symbol(s) {}..{} ({})",
                enabled.size(), symbols.size(), req.start(), req.end(),
                perStrategyTf ? "per-strategy timeframe" : cfg.timeframe.toString());

        List<LeaderboardEntryDto> board = new ArrayList<>();
        int i = 0, total = enabled.size();
        for (Map.Entry<String, TradingStrategy> e : enabled.entrySet()) {
            i++;
            Map<String, Double> params = strategies.getParams(e.getKey());
            BacktestConfig runCfg = cfg;
            List<BarSeries> data = sharedData;
            if (perStrategyTf) {
                Timeframe tf = strategies.recommendedTimeframe(e.getKey());
                runCfg = cfg.withTimeframe(tf);
                data = nativeData.stream().map(b -> BarResampler.resample(b, tf)).toList();
            }
            long t0 = System.currentTimeMillis();
            log.info("Backtest run-all [{}/{}]: '{}' @ {}", i, total, e.getKey(), runCfg.timeframe);
            BacktestOutput o = backtester.runPortfolio(data, e.getValue(), params.isEmpty() ? null : params, runCfg);
            BacktestResultDto dto = persist(o, symbols, runCfg, req.start(), req.end());
            log.info("Backtest run-all [{}/{}]: '{}' done in {} ms — run #{} {}", i, total, e.getKey(),
                    System.currentTimeMillis() - t0, dto.runId(), fmtMetrics(o.metrics));
            board.add(new LeaderboardEntryDto(dto.runId(), dto.strategy(), dto.timeframe(), dto.bars(), dto.metrics()));
        }
        board.sort((a, b) -> Double.compare(
                b.metrics().getOrDefault("sharpe", 0.0), a.metrics().getOrDefault("sharpe", 0.0)));
        log.info("Backtest run-all: {} strategies done in {} ms", total, System.currentTimeMillis() - batchStart);
        return board;
    }

    @Transactional
    public BacktestResultDto runPairs(PairsRequest req) {
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

        long t0 = System.currentTimeMillis();
        log.info("Backtest: pairs {}/{} {}..{} @ {}", req.symbolA(), req.symbolB(),
                req.start(), req.end(), cfg.timeframe);
        BarSeries a = BarResampler.resample(
                marketData.getBars(req.symbolA().toUpperCase(), req.start(), req.end()), cfg.timeframe);
        BarSeries bs = BarResampler.resample(
                marketData.getBars(req.symbolB().toUpperCase(), req.start(), req.end()), cfg.timeframe);
        PairsStrategy strat = new PairsStrategy(
                req.window() != null ? req.window() : 60,
                req.entry() != null ? req.entry() : 2.0,
                req.exit() != null ? req.exit() : 0.5);
        BacktestOutput o = backtester.runPairs(a, bs, strat, cfg);
        BacktestResultDto dto = persist(o, List.of(req.symbolA().toUpperCase(), req.symbolB().toUpperCase()),
                cfg, req.start(), req.end());
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
            trades.add(te);
        }
        tradeRepo.saveAll(trades);

        return toDto(run.getId(), cfg.timeframe.name(), o.dates.length, o);
    }

    public BacktestResultDto getRun(Long runId) {
        BacktestRunEntity run = runRepo.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("No run " + runId));
        BacktestResultEntity res = resultRepo.findByRunId(runId)
                .orElseThrow(() -> new NoSuchElementException("No result for run " + runId));
        List<TradeDto> trades = tradeRepo.findByRunId(runId).stream()
                .map(t -> new TradeDto(t.getSymbol(), t.getSide(), t.getEntryDate(), t.getExitDate(),
                        t.getEntryPx(), t.getExitPx(), t.getBars(), t.getReturnPct()))
                .toList();
        List<String> symbols = run.getSymbolsCsv() == null ? List.of()
                : Arrays.asList(run.getSymbolsCsv().split(","));
        List<String> dates = readStrings(res.getDatesJson());
        String tf = run.getTimeframe() != null ? run.getTimeframe() : Timeframe.NATIVE.name();
        int bars = run.getBars() != null ? run.getBars() : dates.size();
        return new BacktestResultDto(runId, run.getStrategyName(), symbols,
                run.getStartDate(), run.getEndDate(), tf, bars, json.readMetrics(res.getMetricsJson()),
                dates, json.readDoubles(res.getEquityJson()),
                json.readDoubles(res.getBenchmarkJson()), json.readDoubles(res.getDrawdownJson()), trades);
    }

    @Transactional
    public void deleteRun(Long runId) {
        if (!runRepo.existsById(runId)) throw new NoSuchElementException("No run " + runId);
        tradeRepo.deleteByRunId(runId);
        resultRepo.deleteByRunId(runId);
        runRepo.deleteById(runId);
    }

    /**
     * Keep only the {@code keep} best strategies (ranked from run history by {@code by} —
     * "sharpe" or "totalReturnPct", taking each strategy's best run). Every other strategy
     * that has run history is disabled and all of its runs / results / trades are deleted.
     * Strategies with no run history are left untouched.
     */
    @Transactional
    public PruneResultDto pruneToTop(int keep, String by) {
        int k = Math.max(1, keep);
        boolean byReturn = by != null && (by.equalsIgnoreCase("totalReturnPct") || by.equalsIgnoreCase("return"));
        String rankedBy = byReturn ? "totalReturnPct" : "sharpe";

        List<String> ranked = (byReturn ? runRepo.bestReturnPerStrategy() : runRepo.bestSharpePerStrategy())
                .stream()
                .filter(s -> s.getStrategyName() != null)
                .sorted(Comparator.comparingDouble(
                        (BacktestRunRepository.StrategyScore s) ->
                                s.getScore() == null ? Double.NEGATIVE_INFINITY : s.getScore()).reversed())
                .map(BacktestRunRepository.StrategyScore::getStrategyName)
                .toList();

        List<String> kept = ranked.stream().limit(k).toList();
        List<String> losers = ranked.stream().skip(k).toList();

        int deletedRuns = 0;
        for (String name : losers) {
            tradeRepo.deleteByRunStrategyName(name);
            resultRepo.deleteByRunStrategyName(name);
            deletedRuns += runRepo.deleteByStrategyName(name);
            if (strategies.isStrategy(name)) strategies.setEnabled(name, false);
        }
        for (String name : kept)
            if (strategies.isStrategy(name)) strategies.setEnabled(name, true);

        return new PruneResultDto(rankedBy, k, kept, losers, deletedRuns);
    }

    public List<LeaderboardEntryDto> listRuns(String strategy) {
        return listRuns(strategy, null, null, null, null, null, null, null, null, null, null);
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
    public List<LeaderboardEntryDto> listRuns(String strategy, Double minReturn, Double minCagr,
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

        List<BacktestRunEntity> runs = runRepo.filter(
                (strategy == null || strategy.isBlank()) ? null : strategy,
                minReturn, minCagr, minSharpe, minProfitFactor, minWinRate, ddFloor, minTrades,
                org.springframework.data.domain.PageRequest.of(0, lim, s));

        List<LeaderboardEntryDto> out = new ArrayList<>();
        for (BacktestRunEntity r : runs) {
            Map<String, Double> metrics = resultRepo.findByRunId(r.getId())
                    .map(res -> new LinkedHashMap<>(json.readMetrics(res.getMetricsJson())))
                    .orElseGet(LinkedHashMap::new);
            // the denormalised run columns are the authoritative headline figures
            // (BacktestSummaryBackfill repairs legacy compounding-artifact values there),
            // so let them win over the stored JSON blob.
            metrics.putAll(summaryMetrics(r));
            out.add(new LeaderboardEntryDto(r.getId(), r.getStrategyName(),
                    r.getTimeframe() != null ? r.getTimeframe() : Timeframe.NATIVE.name(),
                    r.getBars(), metrics));
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
        List<TradeDto> trades = o.trades.stream()
                .map(t -> new TradeDto(t.symbol, t.side, t.entryDate, t.exitDate,
                        t.entryPx, t.exitPx, t.bars, t.returnPct))
                .toList();
        return new BacktestResultDto(runId, o.strategy, o.symbols, o.startDate, o.endDate,
                timeframe, bars, o.metrics, datesToStrings(o.dates), o.equity, o.benchmark, o.drawdown, trades);
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
