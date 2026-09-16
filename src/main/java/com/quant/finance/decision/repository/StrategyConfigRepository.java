package com.quant.finance.decision.repository;

import com.quant.finance.decision.domain.StrategyConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StrategyConfigRepository extends JpaRepository<StrategyConfigEntity, Long> {
    Optional<StrategyConfigEntity> findByName(String name);
    boolean existsByName(String name);
    void deleteByName(String name);
}
