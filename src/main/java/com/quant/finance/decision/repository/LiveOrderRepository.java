package com.quant.finance.decision.repository;

import com.quant.finance.decision.entity.LiveOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LiveOrderRepository extends JpaRepository<LiveOrderEntity, Long> {
    org.springframework.data.domain.Page<LiveOrderEntity> findAllByOrderByIdDesc(org.springframework.data.domain.Pageable page);
}
