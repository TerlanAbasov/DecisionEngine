package com.quant.finance.decision.web;

import com.quant.finance.decision.data.MarketDataService;
import com.quant.finance.decision.dto.Dtos.PriceSeriesDto;
import com.quant.finance.decision.dto.Dtos.SymbolCoverageDto;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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

    /** Refresh cached bars for the given symbols. */
    @PostMapping("/pull")
    public Map<String, Integer> pull(@RequestParam List<String> symbols) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String s : symbols) out.put(s.toUpperCase(), service.refresh(s.toUpperCase()));
        return out;
    }
}
