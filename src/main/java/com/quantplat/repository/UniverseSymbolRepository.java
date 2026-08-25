package com.quantplat.repository;

import com.quantplat.domain.UniverseSymbolEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UniverseSymbolRepository extends JpaRepository<UniverseSymbolEntity, Long> {
    Optional<UniverseSymbolEntity> findBySymbol(String symbol);
    boolean existsBySymbol(String symbol);
    void deleteBySymbol(String symbol);
}
