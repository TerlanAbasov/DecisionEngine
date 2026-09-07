package com.quantplat.repository;

import com.quantplat.domain.BacktestRunEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BacktestRunRepository extends JpaRepository<BacktestRunEntity, Long> {
    List<BacktestRunEntity> findByStrategyNameOrderByCreatedAtDesc(String strategyName);
    List<BacktestRunEntity> findAllByOrderByCreatedAtDesc();

    /** Best score per strategy across its run history, for ranking. */
    interface StrategyScore {
        String getStrategyName();
        Double getScore();
    }

    @Query("select r.strategyName as strategyName, max(r.sharpe) as score " +
           "from BacktestRunEntity r where r.strategyName is not null group by r.strategyName")
    List<StrategyScore> bestSharpePerStrategy();

    @Query("select r.strategyName as strategyName, max(r.totalReturnPct) as score " +
           "from BacktestRunEntity r where r.strategyName is not null group by r.strategyName")
    List<StrategyScore> bestReturnPerStrategy();

    @Query("select distinct r.strategyName from BacktestRunEntity r where r.strategyName is not null")
    List<String> distinctStrategyNames();

    @Modifying
    @Query("delete from BacktestRunEntity r where r.strategyName = :name")
    int deleteByStrategyName(@Param("name") String name);

    /**
     * Run history with optional filters; each filter is ignored when its argument is null.
     * {@code minDrawdownPct} is the floor for the (negative) max-drawdown column, e.g. pass
     * -25.0 to keep only runs whose drawdown is shallower than -25%. Sort/limit via {@code pageable}.
     */
    @Query("""
            select r from BacktestRunEntity r
            where (:strategy is null or r.strategyName = :strategy)
              and (:minReturn is null or r.totalReturnPct >= :minReturn)
              and (:minCagr is null or r.cagrPct >= :minCagr)
              and (:minSharpe is null or r.sharpe >= :minSharpe)
              and (:minProfitFactor is null or r.profitFactor >= :minProfitFactor)
              and (:minWinRate is null or r.winRatePct >= :minWinRate)
              and (:minDrawdownPct is null or r.maxDrawdownPct >= :minDrawdownPct)
              and (:minTrades is null or r.trades >= :minTrades)
            """)
    List<BacktestRunEntity> filter(@Param("strategy") String strategy,
                                   @Param("minReturn") Double minReturn,
                                   @Param("minCagr") Double minCagr,
                                   @Param("minSharpe") Double minSharpe,
                                   @Param("minProfitFactor") Double minProfitFactor,
                                   @Param("minWinRate") Double minWinRate,
                                   @Param("minDrawdownPct") Double minDrawdownPct,
                                   @Param("minTrades") Integer minTrades,
                                   Pageable pageable);
}
