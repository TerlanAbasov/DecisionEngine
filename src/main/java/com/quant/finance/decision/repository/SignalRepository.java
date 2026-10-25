package com.quant.finance.decision.repository;

import com.quant.finance.decision.entity.SignalEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SignalRepository extends JpaRepository<SignalEntity, Long> {
    List<SignalEntity> findByOrderByCreatedAtDesc();

    void deleteByAsOfDate(java.time.Instant asOfDate);
}
