package com.quant.finance.decision.repository;

import com.quant.finance.decision.entity.UniverseSymbolEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UniverseSymbolRepository extends JpaRepository<UniverseSymbolEntity, Long> {
    Optional<UniverseSymbolEntity> findBySymbol(String symbol);
    boolean existsBySymbol(String symbol);

    @Modifying
    @Query("delete from UniverseSymbolEntity u where u.symbol = :symbol")
    int deleteBySymbol(@Param("symbol") String symbol);
}
