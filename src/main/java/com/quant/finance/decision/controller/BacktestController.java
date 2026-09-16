package com.quant.finance.decision.controller;

import com.quant.finance.decision.dto.Dtos.*;
import com.quant.finance.decision.service.BacktestService;
import com.quant.finance.decision.service.EnsembleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/backtests")
@Slf4j
public class BacktestController {

    private final BacktestService service;
    private final EnsembleService ensemble;

    public BacktestController(BacktestService service, EnsembleService ensemble) {
        this.service = service;
        this.ensemble = ensemble;
    }

    @GetMapping("/timeframes")
    public List<TimeframeDto> timeframes() {
        return service.timeframes();
    }

    @PostMapping
    public BacktestResultDto run(@RequestBody BacktestRequest req) {
        log.info("POST /backtests — strategy='{}' symbols={} {}..{} tf={}", req.strategyName(),
                req.symbols() == null ? "universe" : req.symbols().size(), req.start(), req.end(), req.timeframe());
        return service.run(req);
    }

    @PostMapping("/run-all")
    public List<LeaderboardEntryDto> runAll(@RequestBody BacktestRequest req) {
        log.info("POST /backtests/run-all — symbols={} {}..{} tf={} perStrategyTf={} includeDisabled={} picked={}",
                req.symbols() == null ? "universe" : req.symbols().size(), req.start(), req.end(),
                req.timeframe(), req.perStrategyTimeframe(), req.includeDisabled(),
                req.strategyNames() == null ? 0 : req.strategyNames().size());
        return service.runAll(req);
    }

    @PostMapping("/pairs")
    public BacktestResultDto pairs(@RequestBody PairsRequest req) {
        log.info("POST /backtests/pairs — {}/{} {}..{} tf={}", req.symbolA(), req.symbolB(),
                req.start(), req.end(), req.timeframe());
        return service.runPairs(req);
    }

    @PostMapping("/ensemble")
    public EnsembleResultDto ensemble(@RequestBody EnsembleRequest req) {
        log.info("POST /backtests/ensemble — legs={} weighting={} tf={}",
                req.strategyNames() == null ? "all-enabled" : req.strategyNames().size(),
                req.weighting(), req.timeframe());
        return ensemble.ensemble(req);
    }

    @GetMapping("/{id}")
    public BacktestResultDto get(@PathVariable Long id) {
        return service.getRun(id);
    }

    @GetMapping
    public List<LeaderboardEntryDto> list(
            @RequestParam(required = false) String strategy,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Double minReturn,
            @RequestParam(required = false) Double minCagr,
            @RequestParam(required = false) Double minSharpe,
            @RequestParam(required = false) Double minProfitFactor,
            @RequestParam(required = false) Double minWinRate,
            @RequestParam(required = false) Double maxDrawdown,
            @RequestParam(required = false) Integer minTrades,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam(required = false) Integer limit) {
        return service.listRuns(strategy, symbol, minReturn, minCagr, minSharpe, minProfitFactor, minWinRate,
                maxDrawdown, minTrades, sort, dir, limit);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteRun(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Prune the strategy set to the most profitable subset. Ranks each strategy that has run
     * history by the average of its {@code recentRuns} most recent runs' {@code by} metric
     * ("totalReturnPct" (default) or "sharpe"), keeps {@code keep} strategies or the top
     * {@code keepPct}% (default 50), deletes the rest's runs/results/trades and applies
     * {@code mode} to the strategy itself: "archive" (default, hidden from the UI, reversible),
     * "disable" (stays visible, just skipped by runs), or "delete" (permanently removes its
     * {@code strategy_config} row too — irreversible). Strategies with no run history are untouched.
     */
    @PostMapping("/prune")
    public PruneResultDto prune(@RequestParam(required = false) Integer keep,
                                @RequestParam(required = false) Integer keepPct,
                                @RequestParam(defaultValue = "2") Integer recentRuns,
                                @RequestParam(defaultValue = "totalReturnPct") String by,
                                @RequestParam(defaultValue = "archive") String mode) {
        return service.pruneToTop(keep, keepPct, recentRuns, by, mode);
    }
}
