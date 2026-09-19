package com.quant.finance.decision.controller;

import com.quant.finance.decision.data.MarketDataService;
import com.quant.finance.decision.dto.Dtos.PriceSeriesDto;
import com.quant.finance.decision.dto.Dtos.SymbolCoverageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market-data")
@Slf4j
public class MarketDataController {

    private final MarketDataService service;

    public MarketDataController(MarketDataService service) {
        this.service = service;
    }

    @GetMapping("/source")
    public Map<String, String> source() {
        return Map.of("source", service.activeSource());
    }

    /** Per-symbol cache coverage (first/last bar, count, freshness) for symbols with stored bars. */
    @GetMapping("/symbols")
    public List<SymbolCoverageDto> symbols() {
        return service.coverage();
    }

    /** OHLCV series for the chart view — resampled to {@code timeframe}, most recent {@code limit} bars. */
    @GetMapping("/bars")
    public PriceSeriesDto bars(@RequestParam String symbol,
                               @RequestParam(required = false) String timeframe,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
                               @RequestParam(defaultValue = "1500") int limit) {
        return service.priceSeries(symbol, timeframe, start, end, Math.min(Math.max(limit, 50), 5000));
    }

    /**
     * Refresh cached bars for the given symbols. One symbol's fetch failing (bad ticker, data
     * source hiccup) doesn't abort the rest — its value is {@code -1} instead of a bar count.
     */
    @PostMapping("/pull")
    public Map<String, Integer> pull(@RequestParam List<String> symbols) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String s : symbols) {
            String sym = s.toUpperCase();
            try {
                out.put(sym, service.refresh(sym));
            } catch (RuntimeException e) {
                log.warn("Pull: {} failed — {}: {}", sym, e.getClass().getSimpleName(), e.getMessage());
                out.put(sym, -1);
            }
        }
        return out;
    }

    /** Permanently drops a symbol's cached bars. Not a universe removal — see /api/universe. */
    @DeleteMapping("/{symbol}")
    public Map<String, Integer> purge(@PathVariable String symbol) {
        return Map.of("deleted", service.purge(symbol.toUpperCase()));
    }
}
