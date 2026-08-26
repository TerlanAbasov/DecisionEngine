package com.quantplat.repository;

import com.quantplat.domain.PriceBarEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PriceBarRepository extends JpaRepository<PriceBarEntity, Long> {
    List<PriceBarEntity> findBySymbolOrderByBarDate(String symbol);
    List<PriceBarEntity> findBySymbolAndBarDateBetweenOrderByBarDate(String symbol, LocalDate start, LocalDate end);
    Optional<PriceBarEntity> findTopBySymbolOrderByBarDateDesc(String symbol);
    boolean existsBySymbol(String symbol);
    long deleteBySymbol(String symbol);
}
