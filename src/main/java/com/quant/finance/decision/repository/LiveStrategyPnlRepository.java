package com.quant.finance.decision.repository;

import com.quant.finance.decision.domain.LiveStrategyPnlEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface LiveStrategyPnlRepository extends JpaRepository<LiveStrategyPnlEntity, Long> {
    List<LiveStrategyPnlEntity> findByStrategyAndTsAfterOrderByTs(String strategy, Instant since);

    long deleteByTsBefore(Instant cutoff);
}
