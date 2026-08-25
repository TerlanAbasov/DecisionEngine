package com.quantplat.service;

import com.quantplat.domain.StrategyConfigEntity;
import com.quantplat.dto.Dtos.StrategyDto;
import com.quantplat.repository.StrategyConfigRepository;
import com.quantplat.strategy.TradingStrategy;
import com.quantplat.strategy.impl.StrategyCatalog;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class StrategyService {

    private final StrategyConfigRepository repo;
    private final JsonCodec json;
    private final Map<String, TradingStrategy> catalog = new LinkedHashMap<>();

    public StrategyService(StrategyConfigRepository repo, JsonCodec json) {
        this.repo = repo;
        this.json = json;
        for (TradingStrategy s : StrategyCatalog.all()) catalog.put(s.name(), s);
    }

    /** Seed strategy_config from the catalog on first startup. */
    public void seed() {
        for (TradingStrategy s : catalog.values()) {
            if (!repo.existsByName(s.name())) {
                StrategyConfigEntity e = new StrategyConfigEntity();
                e.setName(s.name());
                e.setCategory(s.category());
                e.setDirection(s.direction());
                e.setDescription(s.description());
                e.setEnabled(true);
                repo.save(e);
            }
        }
    }

    public List<StrategyDto> list() {
        List<StrategyDto> out = new ArrayList<>();
        for (StrategyConfigEntity e : repo.findAll()) {
            TradingStrategy s = catalog.get(e.getName());
            if (s == null) continue;
            out.add(new StrategyDto(e.getName(), e.getCategory(), e.getDirection(),
                    e.getDescription(), e.isEnabled(), json.readParams(e.getParamsJson())));
        }
        out.sort(Comparator.comparing(StrategyDto::name));
        return out;
    }

    public void setEnabled(String name, boolean enabled) {
        StrategyConfigEntity e = repo.findByName(name)
                .orElseThrow(() -> new NoSuchElementException("Unknown strategy: " + name));
        e.setEnabled(enabled);
        repo.save(e);
    }

    public TradingStrategy getStrategy(String name) {
        TradingStrategy s = catalog.get(name);
        if (s == null) throw new NoSuchElementException("Unknown strategy: " + name);
        return s;
    }

    public Map<String, Double> getParams(String name) {
        return repo.findByName(name).map(e -> json.readParams(e.getParamsJson())).orElse(Map.of());
    }

    /** name -> strategy for all enabled strategies. */
    public Map<String, TradingStrategy> getEnabledStrategies() {
        Map<String, TradingStrategy> out = new LinkedHashMap<>();
        for (StrategyConfigEntity e : repo.findAll())
            if (e.isEnabled() && catalog.containsKey(e.getName()))
                out.put(e.getName(), catalog.get(e.getName()));
        return out;
    }

    public String categoryOf(String name) {
        TradingStrategy s = catalog.get(name);
        return s == null ? "?" : s.category();
    }
}
