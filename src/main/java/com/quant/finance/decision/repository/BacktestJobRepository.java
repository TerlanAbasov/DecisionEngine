package com.quant.finance.decision.repository;

import com.quant.finance.decision.domain.BacktestJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface BacktestJobRepository extends JpaRepository<BacktestJobEntity, String> {
    List<BacktestJobEntity> findByStatus(String status);

    @Transactional
    long deleteByCreatedAtBefore(Instant cutoff);
}
