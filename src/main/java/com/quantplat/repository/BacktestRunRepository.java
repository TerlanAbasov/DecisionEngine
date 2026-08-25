package com.quantplat.repository;

import com.quantplat.domain.BacktestRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BacktestRunRepository extends JpaRepository<BacktestRunEntity, Long> {
    List<BacktestRunEntity> findByStrategyNameOrderByCreatedAtDesc(String strategyName);
    List<BacktestRunEntity> findAllByOrderByCreatedAtDesc();
}
