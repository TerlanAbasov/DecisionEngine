package com.quantplat.web;

import com.quantplat.dto.Dtos.SignalDto;
import com.quantplat.execution.ExecutionEngineClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Manual trigger to push a single decided signal to ExecutionEngine, regardless of auto-forward. */
@RestController
@RequestMapping("/api/execution")
public class ExecutionController {

    private final ExecutionEngineClient executionEngine;

    public ExecutionController(ExecutionEngineClient executionEngine) {
        this.executionEngine = executionEngine;
    }

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("configured", executionEngine.isConfigured());
    }

    @PostMapping("/send")
    public Map<String, String> send(@RequestBody SignalDto signal) {
        executionEngine.sendAlert(signal);
        return Map.of("status", "sent", "symbol", signal.symbol(), "strategy", signal.strategy());
    }
}
