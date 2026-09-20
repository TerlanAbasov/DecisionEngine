package com.quant.finance.decision.repository;

import com.quant.finance.decision.domain.LiveConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LiveConfigRepository extends JpaRepository<LiveConfigEntity, Long> {}
