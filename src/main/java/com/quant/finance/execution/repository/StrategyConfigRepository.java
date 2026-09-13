package com.quant.finance.execution.repository;

import com.quant.finance.execution.domain.StrategyConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StrategyConfigRepository extends JpaRepository<StrategyConfigEntity, Long> {
    Optional<StrategyConfigEntity> findByName(String name);
    boolean existsByName(String name);
}
