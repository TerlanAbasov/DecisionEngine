package com.quant.finance.decision.controller;

import com.quant.finance.decision.dto.Dtos.*;
import com.quant.finance.decision.service.BacktestService;
import com.quant.finance.decision.service.EnsembleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/backtests")
@Slf4j
public class BacktestController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

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

    /**
     * A run's trade log, one page at a time: filter by {@code symbol} / {@code side} (LONG|SHORT), {@code sort} by a trade column (default entryDate),
     * {@code dir} asc|desc (default desc); {@code size} is capped at 500 and the summary covers every trade matching the filter.
     */
    @GetMapping("/{id}/trades")
    public TradePageDto trades(@PathVariable Long id,
                               @RequestParam(required = false) String symbol,
                               @RequestParam(required = false) String side,
                               @RequestParam(required = false) String sort,
                               @RequestParam(required = false) String dir,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "50") int size) {
        return service.getTrades(id, symbol, side, sort, dir, page, size);
    }

    /** Every trade matching the same filter and sort as {@link #trades} (no paging) as an Excel workbook, for analysis outside the app. */
    @GetMapping("/{id}/trades/export")
    public ResponseEntity<byte[]> exportTrades(@PathVariable Long id,
                                               @RequestParam(required = false) String symbol,
                                               @RequestParam(required = false) String side,
                                               @RequestParam(required = false) String sort,
                                               @RequestParam(required = false) String dir) {
        byte[] workbook = service.exportTrades(id, symbol, side, sort, dir);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(exportFileName(id, symbol, side)).build().toString())
                .body(workbook);
    }

    /** {@code trades-run-12.xlsx}, or {@code trades-run-12-AAPL-LONG.xlsx} when filtered. */
    private static String exportFileName(Long runId, String symbol, String side) {
        StringBuilder name = new StringBuilder("trades-run-").append(runId);
        for (String filter : new String[]{symbol, side})
            if (filter != null && !filter.isBlank()) name.append('-').append(filter.trim().toUpperCase().replaceAll("[^A-Z0-9._]", "_"));
        return name.append(".xlsx").toString();
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
     * Keeps the top {@code keep} / {@code keepPct}% of strategies ranked by their recent runs' average {@code by} metric (totalReturnPct or sharpe)
     * and deletes the rest's runs, applying {@code mode} to them: archive (default, reversible), disable, or delete (irreversible).
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
