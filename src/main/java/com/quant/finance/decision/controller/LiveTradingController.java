package com.quant.finance.decision.controller;

import com.quant.finance.decision.live.LiveConfigService;
import com.quant.finance.decision.live.LiveDtos.*;
import com.quant.finance.decision.live.LiveQueryService;
import com.quant.finance.decision.live.LiveSettings;
import com.quant.finance.decision.live.LiveTradingScheduler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Paper (demo-account) trading: configure the periodic job, run or stop it, and monitor what it did. Unrelated to
 * the execution-engine forwarding: this trades the Alpaca paper account directly.
 */
@RestController
@RequestMapping("/api/live")
@Slf4j
public class LiveTradingController {

    private final LiveConfigService config;
    private final LiveTradingScheduler scheduler;
    private final LiveQueryService query;

    public LiveTradingController(LiveConfigService config, LiveTradingScheduler scheduler, LiveQueryService query) {
        this.config = config;
        this.scheduler = scheduler;
        this.query = query;
    }

    @GetMapping("/status")
    public StatusDto status() { return query.status(); }

    @GetMapping("/config")
    public LiveSettings getConfig() { return config.current(); }

    /** Replaces the settings (all fields). Enabling starts the schedule; changing the interval re-plans it. */
    @PutMapping("/config")
    public LiveSettings putConfig(@RequestBody LiveSettings settings) {
        log.info("PUT /live/config — enabled={} dryRun={}", settings.enabled(), settings.dryRun());
        return config.update(settings);
    }

    /** One cycle now, in the background, using the saved settings (also when the job is disabled). */
    @PostMapping("/run-now")
    public ResponseEntity<Map<String, String>> runNow() {
        log.info("POST /live/run-now");
        scheduler.runNow();
        return ResponseEntity.accepted().body(Map.of("status", "started"));
    }

    /** Switches the job off and closes every position it holds, in the background. */
    @PostMapping("/flatten")
    public ResponseEntity<Map<String, String>> flatten() {
        log.info("POST /live/flatten");
        scheduler.flatten();
        return ResponseEntity.accepted().body(Map.of("status", "started"));
    }

    @GetMapping("/strategies")
    public List<StrategyPerfDto> strategies() { return query.strategyPerformance(); }

    @GetMapping("/strategies/{name}")
    public StrategyDetailDto strategy(@PathVariable String name, @RequestParam(defaultValue = "100") int trades) {
        return query.strategyDetail(name, trades);
    }

    @GetMapping("/positions")
    public List<PositionDto> positions() { return query.positions(); }

    @GetMapping("/orders")
    public PageDto<OrderDto> orders(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return query.orders(page, size);
    }

    @GetMapping("/cycles")
    public PageDto<CycleDto> cycles(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return query.cycles(page, size);
    }

    @GetMapping("/equity")
    public List<EquityPointDto> equity(@RequestParam(defaultValue = "72") int hours) { return query.equity(hours); }
}
