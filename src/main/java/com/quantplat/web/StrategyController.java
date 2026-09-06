package com.quantplat.web;

import com.quantplat.dto.Dtos.*;
import com.quantplat.service.OptimizerService;
import com.quantplat.service.StrategyService;
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
    public List<StrategyDto> list() {
        return service.list();
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
}
