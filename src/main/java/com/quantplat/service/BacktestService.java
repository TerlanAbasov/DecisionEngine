package com.quantplat.service;

import com.quantplat.data.MarketDataService;
import com.quantplat.domain.*;
import com.quantplat.dto.Dtos.*;
import com.quantplat.engine.*;
import com.quantplat.repository.*;
import com.quantplat.strategy.BarSeries;
import com.quantplat.strategy.TradingStrategy;
import com.quantplat.strategy.impl.PairsStrategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class BacktestService {

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

    private BacktestConfig cfg(BacktestRequest r) {
        return new BacktestConfig(
                r.capital() != null ? r.capital() : 100_000,
                r.commissionBps() != null ? r.commissionBps() : 1.0,
                r.slippageBps() != null ? r.slippageBps() : 2.0,
                r.allowShort() == null || r.allowShort(),
                1);
    }

    private List<String> resolveSymbols(List<String> requested) {
        if (requested != null && !requested.isEmpty())
            return requested.stream().map(String::toUpperCase).toList();
        return universe.get();
    }

    private List<BarSeries> load(List<String> symbols, LocalDate start, LocalDate end) {
        List<BarSeries> out = new ArrayList<>();
        for (String s : symbols) {
            BarSeries b = marketData.getBars(s, start, end);
            if (b.size() > 0) out.add(b);
        }
        if (out.isEmpty()) throw new IllegalStateException("No market data for requested symbols/range");
        return out;
    }

    @Transactional
    public BacktestResultDto run(BacktestRequest req) {
        List<String> symbols = resolveSymbols(req.symbols());
        BacktestConfig cfg = cfg(req);
        TradingStrategy strat = strategies.getStrategy(req.strategyName());
        Map<String, Double> params = strategies.getParams(req.strategyName());
        List<BarSeries> data = load(symbols, req.start(), req.end());
        BacktestOutput o = backtester.runPortfolio(data, strat, params.isEmpty() ? null : params, cfg);
        return persist(o, symbols, cfg, req.start(), req.end());
    }

    @Transactional
    public List<LeaderboardEntryDto> runAll(BacktestRequest req) {
        List<String> symbols = resolveSymbols(req.symbols());
        BacktestConfig cfg = cfg(req);
        List<BarSeries> data = load(symbols, req.start(), req.end());
        List<LeaderboardEntryDto> board = new ArrayList<>();
        for (Map.Entry<String, TradingStrategy> e : strategies.getEnabledStrategies().entrySet()) {
            Map<String, Double> params = strategies.getParams(e.getKey());
            BacktestOutput o = backtester.runPortfolio(data, e.getValue(), params.isEmpty() ? null : params, cfg);
            BacktestResultDto dto = persist(o, symbols, cfg, req.start(), req.end());
            board.add(new LeaderboardEntryDto(dto.runId(), dto.strategy(), dto.metrics()));
        }
        board.sort((a, b) -> Double.compare(
                b.metrics().getOrDefault("sharpe", 0.0), a.metrics().getOrDefault("sharpe", 0.0)));
        return board;
    }

    @Transactional
    public BacktestResultDto runPairs(PairsRequest req) {
        BacktestConfig cfg = new BacktestConfig(
                req.capital() != null ? req.capital() : 100_000,
                req.commissionBps() != null ? req.commissionBps() : 1.0,
                req.slippageBps() != null ? req.slippageBps() : 2.0, true, 1);
        BarSeries a = marketData.getBars(req.symbolA().toUpperCase(), req.start(), req.end());
        BarSeries b = marketData.getBars(req.symbolB().toUpperCase(), req.start(), req.end());
        PairsStrategy strat = new PairsStrategy(
                req.window() != null ? req.window() : 60,
                req.entry() != null ? req.entry() : 2.0,
                req.exit() != null ? req.exit() : 0.5);
        BacktestOutput o = backtester.runPairs(a, b, strat, cfg);
        return persist(o, List.of(req.symbolA().toUpperCase(), req.symbolB().toUpperCase()), cfg, req.start(), req.end());
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

        return toDto(run.getId(), o);
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
        return new BacktestResultDto(runId, run.getStrategyName(), symbols,
                run.getStartDate(), run.getEndDate(), json.readMetrics(res.getMetricsJson()),
                readStrings(res.getDatesJson()), json.readDoubles(res.getEquityJson()),
                json.readDoubles(res.getBenchmarkJson()), json.readDoubles(res.getDrawdownJson()), trades);
    }

    public List<LeaderboardEntryDto> listRuns(String strategy) {
        List<BacktestRunEntity> runs = (strategy == null || strategy.isBlank())
                ? runRepo.findAllByOrderByCreatedAtDesc()
                : runRepo.findByStrategyNameOrderByCreatedAtDesc(strategy);
        List<LeaderboardEntryDto> out = new ArrayList<>();
        for (BacktestRunEntity r : runs) {
            resultRepo.findByRunId(r.getId()).ifPresent(res ->
                    out.add(new LeaderboardEntryDto(r.getId(), r.getStrategyName(),
                            json.readMetrics(res.getMetricsJson()))));
        }
        return out;
    }

    private BacktestResultDto toDto(Long runId, BacktestOutput o) {
        List<TradeDto> trades = o.trades.stream()
                .map(t -> new TradeDto(t.symbol, t.side, t.entryDate, t.exitDate,
                        t.entryPx, t.exitPx, t.bars, t.returnPct))
                .toList();
        return new BacktestResultDto(runId, o.strategy, o.symbols, o.startDate, o.endDate,
                o.metrics, datesToStrings(o.dates), o.equity, o.benchmark, o.drawdown, trades);
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
