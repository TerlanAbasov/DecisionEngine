package com.quant.finance.decision.repository;

import com.quant.finance.decision.domain.PriceBarEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PriceBarRepository extends JpaRepository<PriceBarEntity, Long> {
    List<PriceBarEntity> findBySymbolOrderByBarTime(String symbol);
    List<PriceBarEntity> findBySymbolAndBarTimeBetweenOrderByBarTime(String symbol, Instant start, Instant end);
    Optional<PriceBarEntity> findTopBySymbolOrderByBarTimeDesc(String symbol);
    boolean existsBySymbol(String symbol);

    /**
     * Bulk delete, not the entity-by-entity derived kind: Hibernate flushes a unit of work's
     * inserts before its deletes regardless of call order, so a plain derived
     * {@code deleteBySymbol} deferred to the same flush as a following {@code saveAll} of fresh
     * bars collides with the still-present old rows on the first overlapping timestamp. A bulk
     * {@code @Modifying} query executes immediately instead of waiting for flush.
     */
    @Modifying
    @Query("delete from PriceBarEntity b where b.symbol = :symbol")
    int deleteBySymbol(@Param("symbol") String symbol);

    /** Drop every cached bar not fetched at the given timeframe (legacy nulls included). */
    @Modifying
    @Query("delete from PriceBarEntity b where b.timeframe is null or upper(b.timeframe) <> upper(:timeframe)")
    int deleteByTimeframeNot(@Param("timeframe") String timeframe);

    /** Per-symbol cache coverage: first/last cached bar, bar count and timeframe, one row per symbol. */
    @Query("""
            select b.symbol       as symbol,
                   min(b.barTime) as firstBar,
                   max(b.barTime) as lastBar,
                   count(b)       as bars,
                   max(b.timeframe) as timeframe
            from PriceBarEntity b
            group by b.symbol
            order by b.symbol
            """)
    List<SymbolCoverageRow> findSymbolCoverage();

    /** Projection for {@link #findSymbolCoverage()}. */
    interface SymbolCoverageRow {
        String getSymbol();
        Instant getFirstBar();
        Instant getLastBar();
        long getBars();
        String getTimeframe();
    }
}
