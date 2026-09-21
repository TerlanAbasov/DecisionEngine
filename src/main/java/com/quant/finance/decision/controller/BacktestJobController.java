package com.quant.finance.decision.controller;

import com.quant.finance.decision.dto.Dtos.*;
import com.quant.finance.decision.job.BacktestJobService;
import com.quant.finance.decision.domain.JobKind;
import com.quant.finance.decision.service.BacktestService;
import com.quant.finance.decision.service.EnsembleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Backtests as background jobs: POSTs return 202 with the job, the UI polls {@code GET /{id}} and gets the result once COMPLETED.
 * Only one job runs at a time — a second start gets 409 with the active job.
 */
@RestController
@RequestMapping("/api/backtests/jobs")
@Slf4j
public class BacktestJobController {

    private final BacktestJobService jobs;
    private final BacktestService backtests;
    private final EnsembleService ensemble;

    public BacktestJobController(BacktestJobService jobs, BacktestService backtests, EnsembleService ensemble) {
        this.jobs = jobs;
        this.backtests = backtests;
        this.ensemble = ensemble;
    }

    @PostMapping("/run")
    public ResponseEntity<JobDto> run(@RequestBody BacktestRequest req) {
        if (req.strategyName() == null || req.strategyName().isBlank())
            throw new IllegalArgumentException("strategyName is required");
        return started(jobs.submit(JobKind.RUN, req.strategyName() + " · " + scope(req.symbols()) + tf(req.timeframe()),
                req, p -> backtests.run(req, p)));
    }

    @PostMapping("/run-all")
    public ResponseEntity<JobDto> runAll(@RequestBody BacktestRequest req) {
        int picked = req.strategyNames() == null ? 0 : req.strategyNames().size();
        String what = picked > 0 ? picked + " picked strateg" + (picked == 1 ? "y" : "ies") : "All strategies";
        return started(jobs.submit(JobKind.RUN_ALL, what + " · " + scope(req.symbols()) + tf(req.timeframe()),
                req, p -> backtests.runAll(req, p)));
    }

    @PostMapping("/pairs")
    public ResponseEntity<JobDto> pairs(@RequestBody PairsRequest req) {
        if (blank(req.symbolA()) || blank(req.symbolB()))
            throw new IllegalArgumentException("symbolA and symbolB are required");
        return started(jobs.submit(JobKind.PAIRS,
                "Pairs " + req.symbolA().toUpperCase() + "/" + req.symbolB().toUpperCase() + tf(req.timeframe()),
                req, p -> backtests.runPairs(req, p)));
    }

    @PostMapping("/ensemble")
    public ResponseEntity<JobDto> ensemble(@RequestBody EnsembleRequest req) {
        int legs = req.strategyNames() == null ? 0 : req.strategyNames().size();
        String what = legs > 0 ? "Ensemble of " + legs + " strateg" + (legs == 1 ? "y" : "ies") : "Ensemble of all enabled strategies";
        return started(jobs.submit(JobKind.ENSEMBLE, what + " · " + scope(req.symbols()) + tf(req.timeframe()),
                req, p -> ensemble.ensemble(req, p)));
    }

    @GetMapping("/active")
    public List<JobDto> active() {
        return jobs.active();
    }

    @GetMapping("/{id}")
    public JobDto get(@PathVariable String id) {
        return jobs.get(id);
    }

    @PostMapping("/{id}/cancel")
    public JobDto cancel(@PathVariable String id) {
        return jobs.cancel(id);
    }

    private ResponseEntity<JobDto> started(JobDto job) {
        return ResponseEntity.accepted().body(job);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String scope(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return "universe";
        return symbols.size() <= 4 ? String.join(", ", symbols) : symbols.size() + " symbols";
    }

    private static String tf(String timeframe) {
        return blank(timeframe) || "AUTO".equalsIgnoreCase(timeframe) ? "" : " · " + timeframe;
    }
}
