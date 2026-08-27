package com.quantplat.web;

import com.quantplat.service.UniverseService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/universe")
public class UniverseController {

    private final UniverseService service;

    public UniverseController(UniverseService service) {
        this.service = service;
    }

    @GetMapping
    public List<String> get() {
        return service.get();
    }

    @PutMapping
    public List<String> set(@RequestBody List<String> symbols) {
        return service.set(symbols);
    }

    @PostMapping
    public List<String> add(@RequestBody List<String> symbols) {
        return service.addAll(symbols);
    }
}
