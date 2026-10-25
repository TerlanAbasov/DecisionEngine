package com.quant.finance.decision.controller;

import com.quant.finance.decision.dto.Dtos.OptimizeRequest;
import com.quant.finance.decision.dto.Dtos.OptimizeResultDto;
import com.quant.finance.decision.dto.Dtos.RiskOptimizeBulkResultDto;
import com.quant.finance.decision.dto.Dtos.RiskOptimizeRequest;
import com.quant.finance.decision.dto.Dtos.RiskOptimizeResultDto;
import com.quant.finance.decision.dto.Dtos.StrategyControlsUpdate;
import com.quant.finance.decision.dto.Dtos.StrategyDto;
import com.quant.finance.decision.service.OptimizerService;
import com.quant.finance.decision.service.StrategyService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    public OptimizeResultDto optimize(@PathVariable String name, @RequestBody OptimizeRequest request) {
        return optimizer.optimize(name, request);
    }

    /** Finds the best timeframe/stop-loss%/take-profit% for one strategy; does not save it. */
    @PostMapping("/{name}/optimize-risk-defaults")
    public RiskOptimizeResultDto optimizeRiskDefaults(
            @PathVariable String name, @RequestBody RiskOptimizeRequest request) {
        return optimizer.optimizeRiskDefaults(
                name,
                request.symbols(),
                request.start(),
                request.end(),
                request.capital(),
                request.commissionBps(),
                request.slippageBps(),
                request.allowShort());
    }

    /** Runs the risk-default sweep for every enabled strategy and saves each winner. */
    @PostMapping("/optimize-risk-defaults/bulk")
    public RiskOptimizeBulkResultDto optimizeRiskDefaultsBulk(@RequestBody RiskOptimizeRequest request) {
        return optimizer.optimizeRiskDefaultsBulk(
                request.symbols(),
                request.start(),
                request.end(),
                request.capital(),
                request.commissionBps(),
                request.slippageBps(),
                request.allowShort());
    }
}
