package com.quantplat.repository;

import com.quantplat.domain.BacktestResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface BacktestResultRepository extends JpaRepository<BacktestResultEntity, Long> {
    Optional<BacktestResultEntity> findByRunId(Long runId);
}
