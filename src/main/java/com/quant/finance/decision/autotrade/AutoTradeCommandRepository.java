package com.quant.finance.decision.autotrade;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface AutoTradeCommandRepository extends JpaRepository<AutoTradeCommandEntity, Long> {
    Page<AutoTradeCommandEntity> findAllByOrderByIdDesc(Pageable page);

    /** Whether this bar already has a command (the same thing the unique key enforces), checked before inserting so a routine duplicate never touches the database as a failed write. */
    boolean existsByStrategyAndSymbolAndTimeframeAndBarOpen(String strategy, String symbol, String timeframe, Instant barOpen);
}
