package com.quant.finance.execution.repository;

import com.quant.finance.execution.domain.SignalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SignalRepository extends JpaRepository<SignalEntity, Long> {
    List<SignalEntity> findByOrderByCreatedAtDesc();
    void deleteByAsOfDate(java.time.Instant asOfDate);
}
