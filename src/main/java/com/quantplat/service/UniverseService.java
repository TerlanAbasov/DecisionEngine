package com.quantplat.service;

import com.quantplat.domain.UniverseSymbolEntity;
import com.quantplat.repository.UniverseSymbolRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
public class UniverseService {

    private static final List<String> DEFAULTS = List.of(
            "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "JPM", "XOM", "UNH");

    private final UniverseSymbolRepository repo;

    public UniverseService(UniverseSymbolRepository repo) {
        this.repo = repo;
    }

    public void seedDefaults() {
        if (repo.count() == 0)
            for (String s : DEFAULTS) repo.save(new UniverseSymbolEntity(s));
    }

    public List<String> get() {
        return repo.findAll().stream().map(UniverseSymbolEntity::getSymbol).sorted().toList();
    }

    @Transactional
    public List<String> set(List<String> symbols) {
        repo.deleteAll();
        symbols.stream().map(String::trim).filter(s -> !s.isEmpty())
                .map(String::toUpperCase).distinct().sorted()
                .forEach(s -> repo.save(new UniverseSymbolEntity(s)));
        return get();
    }

    @Transactional
    public void add(String symbol) {
        String s = symbol.trim().toUpperCase();
        if (!s.isEmpty() && !repo.existsBySymbol(s)) repo.save(new UniverseSymbolEntity(s));
    }
}
