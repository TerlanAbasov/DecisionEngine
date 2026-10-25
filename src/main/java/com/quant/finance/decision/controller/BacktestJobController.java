package com.quant.finance.decision.controller;

import com.quant.finance.decision.domain.JobKind;
import com.quant.finance.decision.dto.Dtos.BacktestRequest;
import com.quant.finance.decision.dto.Dtos.EnsembleRequest;
import com.quant.finance.decision.dto.Dtos.JobDto;
import com.quant.finance.decision.dto.Dtos.PairsRequest;
import com.quant.finance.decision.job.BacktestJobService;
import com.quant.finance.decision.service.BacktestService;
import com.quant.finance.decision.service.EnsembleService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<JobDto> run(@RequestBody BacktestRequest request) {
        if (request.strategyName() == null || request.strategyName().isBlank()) {
            throw new IllegalArgumentException("strategyName is required");
        }
        return started(jobs.submit(
                JobKind.RUN,
                request.strategyName() + " · " + scope(request.symbols()) + tf(request.timeframe()),
                request,
                p -> backtests.run(request, p)));
    }

    @PostMapping("/run-all")
    public ResponseEntity<JobDto> runAll(@RequestBody BacktestRequest request) {
        int picked =
                request.strategyNames() == null ? 0 : request.strategyNames().size();
        String what = picked > 0 ? picked + " picked strateg" + (picked == 1 ? "y" : "ies") : "All strategies";
        return started(jobs.submit(
                JobKind.RUN_ALL,
                what + " · " + scope(request.symbols()) + tf(request.timeframe()),
                request,
                p -> backtests.runAll(request, p)));
    }

    @PostMapping("/pairs")
    public ResponseEntity<JobDto> pairs(@RequestBody PairsRequest request) {
        if (blank(request.symbolA()) || blank(request.symbolB())) {
            throw new IllegalArgumentException("symbolA and symbolB are required");
        }
        return started(jobs.submit(
                JobKind.PAIRS,
                "Pairs " + request.symbolA().toUpperCase() + "/"
                        + request.symbolB().toUpperCase() + tf(request.timeframe()),
                request,
                p -> backtests.runPairs(request, p)));
    }

    @PostMapping("/ensemble")
    public ResponseEntity<JobDto> ensemble(@RequestBody EnsembleRequest request) {
        int legs = request.strategyNames() == null ? 0 : request.strategyNames().size();
        String what = legs > 0
                ? "Ensemble of " + legs + " strateg" + (legs == 1 ? "y" : "ies")
                : "Ensemble of all enabled strategies";
        return started(jobs.submit(
                JobKind.ENSEMBLE,
                what + " · " + scope(request.symbols()) + tf(request.timeframe()),
                request,
                p -> ensemble.ensemble(request, p)));
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String scope(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return "universe";
        }
        return symbols.size() <= 4 ? String.join(", ", symbols) : symbols.size() + " symbols";
    }

    private static String tf(String timeframe) {
        return blank(timeframe) || "AUTO".equalsIgnoreCase(timeframe) ? "" : " · " + timeframe;
    }
}
