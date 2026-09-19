package com.quant.finance.decision.repository;

import com.quant.finance.decision.domain.TradeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TradeRepository extends JpaRepository<TradeEntity, Long> {
    List<TradeEntity> findByRunId(Long runId);
    long countByRunId(Long runId);

    @Modifying
    @Query("delete from TradeEntity t where t.run.id = :runId")
    int deleteByRunId(@Param("runId") Long runId);

    @Modifying
    @Query("delete from TradeEntity t where t.run.id in " +
           "(select b.id from BacktestRunEntity b where b.strategyName = :name)")
    int deleteByRunStrategyName(@Param("name") String name);

    /** Sum of realised per-trade returns (fraction) for a run — the fixed-notional total return. */
    @Query("select coalesce(sum(t.returnPct), 0) from TradeEntity t where t.run.id = :runId")
    double sumReturnPctByRunId(@Param("runId") Long runId);
}
