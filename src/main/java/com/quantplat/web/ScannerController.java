package com.quantplat.web;

import com.quantplat.dto.Dtos.SignalDto;
import com.quantplat.service.ScannerService;
import org.springframework.web.bind.annotation.*;

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
                                @RequestParam(defaultValue = "false") boolean includeFlat) {
        return service.scan(symbols, includeFlat);
    }

    @GetMapping("/signals")
    public List<SignalDto> latest() {
        return service.latest();
    }
}
