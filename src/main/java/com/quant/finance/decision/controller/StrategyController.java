package com.quant.finance.decision.controller;

import com.quant.finance.decision.dto.Dtos.*;
import com.quant.finance.decision.service.OptimizerService;
import com.quant.finance.decision.service.StrategyService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/strategies")
public class StrategyController {

    private final StrategyService service;
    private final OptimizerService optimizer;

    public StrategyController(StrategyService service, OptimizerService optimizer) {
        this.service = service;
        this.optimizer = optimizer;
    }

    @GetMapping
    public List<StrategyDto> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(includeArchived);
    }

    /** Restore every archived strategy (enabled flag left unchanged). */
    @PostMapping("/unarchive-all")
    public Map<String, Integer> unarchiveAll() {
        return Map.of("unarchived", service.unarchiveAll());
    }

    @GetMapping("/{name}")
    public StrategyDto get(@PathVariable String name) {
        return service.getDetail(name);
    }

    @PutMapping("/{name}/enabled")
    public void setEnabled(@PathVariable String name, @RequestParam boolean enabled) {
        service.setEnabled(name, enabled);
    }

    @PutMapping("/{name}/params")
    public StrategyDto setParams(@PathVariable String name, @RequestBody Map<String, Double> params) {
        return service.updateParams(name, params);
    }

    @DeleteMapping("/{name}/params")
    public StrategyDto resetParams(@PathVariable String name) {
        return service.resetParams(name);
    }

    @PutMapping("/{name}/controls")
    public StrategyDto setControls(@PathVariable String name, @RequestBody StrategyControlsUpdate update) {
        return service.updateControls(name, update);
    }

    @PostMapping("/{name}/optimize")
    public OptimizeResultDto optimize(@PathVariable String name, @RequestBody OptimizeRequest req) {
        return optimizer.optimize(name, req);
    }

    /** Finds the best timeframe/stop-loss%/take-profit% for one strategy; does not save it. */
    @PostMapping("/{name}/optimize-risk-defaults")
    public RiskOptimizeResultDto optimizeRiskDefaults(@PathVariable String name, @RequestBody RiskOptimizeRequest req) {
        return optimizer.optimizeRiskDefaults(name, req.symbols(), req.start(), req.end(),
                req.capital(), req.commissionBps(), req.slippageBps(), req.allowShort());
    }

    /** Runs the risk-default sweep for every enabled strategy and saves each winner. */
    @PostMapping("/optimize-risk-defaults/bulk")
    public RiskOptimizeBulkResultDto optimizeRiskDefaultsBulk(@RequestBody RiskOptimizeRequest req) {
        return optimizer.optimizeRiskDefaultsBulk(req.symbols(), req.start(), req.end(),
                req.capital(), req.commissionBps(), req.slippageBps(), req.allowShort());
    }
}
