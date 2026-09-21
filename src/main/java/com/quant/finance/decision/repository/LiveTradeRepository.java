package com.quant.finance.decision.repository;

import com.quant.finance.decision.entity.LiveTradeEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LiveTradeRepository extends JpaRepository<LiveTradeEntity, Long> {
    List<LiveTradeEntity> findByStrategyOrderByExitTimeDesc(String strategy, Pageable page);

    @Query("select t.strategy as strategy, count(t) as trades, sum(t.pnlUsd) as pnl, "
            + "sum(case when t.pnlUsd > 0 then 1 else 0 end) as wins from LiveTradeEntity t group by t.strategy")
    List<StrategyTradeStats> statsPerStrategy();

    interface StrategyTradeStats {
        String getStrategy();
        long getTrades();
        Double getPnl();
        Long getWins();
    }
}
