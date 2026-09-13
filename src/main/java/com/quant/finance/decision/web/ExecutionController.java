package com.quant.finance.decision.web;

import com.quant.finance.decision.dto.Dtos.SignalDto;
import com.quant.finance.decision.client.ExecutionEngineClient;
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

    /** Same as /send, but via ExecutionEngine's typed trade-command endpoint (POST
     *  /api/v1/trades/command) instead of its TradingView-shaped alert webhook. */
    @PostMapping("/send-command")
    public Map<String, String> sendCommand(@RequestBody SignalDto signal) {
        executionEngine.sendTradeCommand(signal);
        return Map.of("status", "sent", "symbol", signal.symbol(), "strategy", signal.strategy());
    }
}
