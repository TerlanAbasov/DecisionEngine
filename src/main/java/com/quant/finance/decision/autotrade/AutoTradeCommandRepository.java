package com.quant.finance.decision.autotrade;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutoTradeCommandRepository extends JpaRepository<AutoTradeCommandEntity, Long> {
    Page<AutoTradeCommandEntity> findAllByOrderByIdDesc(Pageable page);
}
