package com.quantplat.web;

import com.quantplat.data.MarketDataService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market-data")
public class MarketDataController {

    private final MarketDataService service;

    public MarketDataController(MarketDataService service) {
        this.service = service;
    }

    @GetMapping("/source")
    public Map<String, String> source() {
        return Map.of("source", service.activeSource());
    }

    /** Refresh cached bars for the given symbols. */
    @PostMapping("/pull")
    public Map<String, Integer> pull(@RequestParam List<String> symbols) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String s : symbols) out.put(s.toUpperCase(), service.refresh(s.toUpperCase()));
        return out;
    }
}
