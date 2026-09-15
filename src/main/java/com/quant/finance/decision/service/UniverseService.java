package com.quant.finance.decision.service;

import com.quant.finance.decision.domain.UniverseSymbolEntity;
import com.quant.finance.decision.repository.UniverseSymbolRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class UniverseService {

  private static final List<String> DEFAULTS = List.of("MU", "SNDK");

  private final UniverseSymbolRepository repo;

  public UniverseService(UniverseSymbolRepository repo) {
    this.repo = repo;
  }

  public void seedDefaults() {
    if (repo.count() == 0) {
      for (String s : DEFAULTS) {
        repo.save(new UniverseSymbolEntity(s));
      }
      log.info("Universe: seeded {} default symbols", DEFAULTS.size());
    }
  }

  public List<String> get() {
    return repo.findAll().stream().map(UniverseSymbolEntity::getSymbol).sorted().toList();
  }

  @Transactional
  public List<String> set(List<String> symbols) {
    // deleteAllInBatch() issues the DELETE immediately; a plain deleteAll() only
    // queues em.remove() calls, which Hibernate flushes *after* the inserts below,
    // so re-adding an existing symbol collides with uk_universe_symbol.
    repo.deleteAllInBatch();
    symbols.stream().map(String::trim).filter(s -> !s.isEmpty())
        .map(String::toUpperCase).distinct().sorted()
        .forEach(s -> repo.save(new UniverseSymbolEntity(s)));
    List<String> now = get();
    log.info("Universe: replaced -> {} symbols {}", now.size(), now);
    return now;
  }

  @Transactional
  public List<String> remove(String symbol) {
    if (symbol != null) {
      repo.deleteBySymbol(symbol.trim().toUpperCase());
      log.info("Universe: removed {}", symbol.trim().toUpperCase());
    }
    return get();
  }

  @Transactional
  public void add(String symbol) {
    String s = symbol.trim().toUpperCase();
    if (!s.isEmpty() && !repo.existsBySymbol(s)) {
      repo.save(new UniverseSymbolEntity(s));
      log.info("Universe: added {}", s);
    }
  }

  @Transactional
  public List<String> addAll(List<String> symbols) {
    symbols.forEach(this::add);
    return get();
  }
}
