package com.quant.finance.execution.repository;

import com.quant.finance.execution.domain.BacktestResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BacktestResultRepository extends JpaRepository<BacktestResultEntity, Long> {
    Optional<BacktestResultEntity> findByRunId(Long runId);

    @Modifying
    @Query("delete from BacktestResultEntity r where r.run.id = :runId")
    int deleteByRunId(@Param("runId") Long runId);

    @Modifying
    @Query("delete from BacktestResultEntity r where r.run.id in " +
           "(select b.id from BacktestRunEntity b where b.strategyName = :name)")
    int deleteByRunStrategyName(@Param("name") String name);
}
