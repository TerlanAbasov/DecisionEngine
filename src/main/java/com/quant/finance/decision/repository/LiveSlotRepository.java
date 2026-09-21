package com.quant.finance.decision.repository;

import com.quant.finance.decision.entity.LiveSlotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LiveSlotRepository extends JpaRepository<LiveSlotEntity, Long> {
    List<LiveSlotEntity> findByStrategy(String strategy);
}
