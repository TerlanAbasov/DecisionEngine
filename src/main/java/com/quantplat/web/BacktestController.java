package com.quantplat.web;

import com.quantplat.dto.Dtos.*;
import com.quantplat.service.BacktestService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/backtests")
public class BacktestController {

    private final BacktestService service;

    public BacktestController(BacktestService service) {
        this.service = service;
    }

    @PostMapping
    public BacktestResultDto run(@RequestBody BacktestRequest req) {
        return service.run(req);
    }

    @PostMapping("/run-all")
    public List<LeaderboardEntryDto> runAll(@RequestBody BacktestRequest req) {
        return service.runAll(req);
    }

    @PostMapping("/pairs")
    public BacktestResultDto pairs(@RequestBody PairsRequest req) {
        return service.runPairs(req);
    }

    @GetMapping("/{id}")
    public BacktestResultDto get(@PathVariable Long id) {
        return service.getRun(id);
    }

    @GetMapping
    public List<LeaderboardEntryDto> list(@RequestParam(required = false) String strategy) {
        return service.listRuns(strategy);
    }
}
