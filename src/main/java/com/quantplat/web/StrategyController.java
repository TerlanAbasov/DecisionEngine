package com.quantplat.web;

import com.quantplat.dto.Dtos.StrategyDto;
import com.quantplat.service.StrategyService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/strategies")
public class StrategyController {

    private final StrategyService service;

    public StrategyController(StrategyService service) {
        this.service = service;
    }

    @GetMapping
    public List<StrategyDto> list() {
        return service.list();
    }

    @PutMapping("/{name}/enabled")
    public void setEnabled(@PathVariable String name, @RequestParam boolean enabled) {
        service.setEnabled(name, enabled);
    }
}
