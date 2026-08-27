package com.quantplat.repository;

import com.quantplat.domain.PriceBarEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PriceBarRepository extends JpaRepository<PriceBarEntity, Long> {
    List<PriceBarEntity> findBySymbolOrderByBarTime(String symbol);
    List<PriceBarEntity> findBySymbolAndBarTimeBetweenOrderByBarTime(String symbol, Instant start, Instant end);
    Optional<PriceBarEntity> findTopBySymbolOrderByBarTimeDesc(String symbol);
    boolean existsBySymbol(String symbol);
    long deleteBySymbol(String symbol);
}
