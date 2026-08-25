package com.quantplat.repository;

import com.quantplat.domain.PriceBarEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface PriceBarRepository extends JpaRepository<PriceBarEntity, Long> {
    List<PriceBarEntity> findBySymbolOrderByBarDate(String symbol);
    List<PriceBarEntity> findBySymbolAndBarDateBetweenOrderByBarDate(String symbol, LocalDate start, LocalDate end);
    boolean existsBySymbol(String symbol);
    long deleteBySymbol(String symbol);
}
