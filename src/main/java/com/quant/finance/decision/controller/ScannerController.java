package com.quant.finance.decision.controller;

import com.quant.finance.decision.dto.Dtos.SignalDto;
import com.quant.finance.decision.dto.Dtos.SignalOverlayDto;
import com.quant.finance.decision.service.ScannerService;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ScannerController {

    private final ScannerService service;

    public ScannerController(ScannerService service) {
        this.service = service;
    }

    @PostMapping("/scan")
    public List<SignalDto> scan(@RequestBody(required = false) List<String> symbols,
                                @RequestParam(defaultValue = "false") boolean includeFlat,
                                @RequestParam(required = false) String timeframe) {
        return service.scan(symbols, includeFlat, timeframe);
    }

    @GetMapping("/signals")
    public List<SignalDto> latest() {
        return service.latest();
    }

    /**
     * Buy/sell/exit markers for the chart overlay. {@code strategies} is a comma/space list of
     * strategy names; {@code timeframe} blank or "AUTO" runs each on its own recommended frame.
     */
    @GetMapping("/signals/chart")
    public SignalOverlayDto chartSignals(@RequestParam String symbol,
                                         @RequestParam(required = false) String timeframe,
                                         @RequestParam String strategies,
                                         @RequestParam(defaultValue = "1500") int limit) {
        List<String> names = Arrays.stream(strategies.split("[,\\s]+")).filter(s -> !s.isBlank()).toList();
        return service.chartSignals(symbol, timeframe, names, Math.min(Math.max(limit, 50), 5000));
    }
}
