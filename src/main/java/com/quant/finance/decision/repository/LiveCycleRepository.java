package com.quant.finance.decision.repository;

import com.quant.finance.decision.entity.LiveCycleEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LiveCycleRepository extends JpaRepository<LiveCycleEntity, Long> {
    Page<LiveCycleEntity> findAllByOrderByIdDesc(Pageable page);

    List<LiveCycleEntity> findByStatus(String status);
}
