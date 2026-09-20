package com.quant.finance.decision.repository;

import com.quant.finance.decision.domain.LiveEquityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface LiveEquityRepository extends JpaRepository<LiveEquityEntity, Long> {
    List<LiveEquityEntity> findByTsAfterOrderByTs(Instant since);

    long deleteByTsBefore(Instant cutoff);
}
