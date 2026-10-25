package com.quant.finance.decision.repository;

import com.quant.finance.decision.entity.BacktestJobEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface BacktestJobRepository extends JpaRepository<BacktestJobEntity, String> {
    List<BacktestJobEntity> findByStatus(String status);

    @Transactional
    long deleteByCreatedAtBefore(Instant cutoff);
}
