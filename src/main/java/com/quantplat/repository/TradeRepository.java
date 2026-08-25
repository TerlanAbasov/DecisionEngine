package com.quantplat.repository;

import com.quantplat.domain.TradeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TradeRepository extends JpaRepository<TradeEntity, Long> {
    List<TradeEntity> findByRunId(Long runId);
}
