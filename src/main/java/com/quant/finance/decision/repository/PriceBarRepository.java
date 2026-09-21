package com.quant.finance.decision.repository;

import com.quant.finance.decision.entity.PriceBarEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PriceBarRepository extends JpaRepository<PriceBarEntity, Long> {
    /*
     * Bulk bar reads return plain rows, not entities. They load hundreds of thousands of rows (about a million
     * for a few symbols of 1-minute data) only to copy them into a BarSeries; as managed entities every later
     * query in the same transaction (a backtest run is one) auto-flushes and walks all of them, which cost
     * roughly a second per query — even loaded read-only, since the flush still visits every managed entity.
     */
    @Query("select new com.quant.finance.decision.repository.BarRow(b.barTime, b.open, b.high, b.low, b.close, b.volume) "
            + "from PriceBarEntity b where b.symbol = :symbol order by b.barTime")
    List<BarRow> findBarsBySymbol(@Param("symbol") String symbol);

    @Query("select new com.quant.finance.decision.repository.BarRow(b.barTime, b.open, b.high, b.low, b.close, b.volume) "
            + "from PriceBarEntity b where b.symbol = :symbol and b.barTime between :from and :to order by b.barTime")
    List<BarRow> findBarsBySymbolBetween(@Param("symbol") String symbol, @Param("from") Instant from, @Param("to") Instant to);

    Optional<PriceBarEntity> findTopBySymbolOrderByBarTimeDesc(String symbol);
    boolean existsBySymbol(String symbol);

    /**
     * Bulk delete, not the derived kind: Hibernate flushes inserts before deletes, so a derived {@code deleteBySymbol} followed by {@code saveAll} of fresh bars
     * would collide with the old rows on the first overlapping timestamp; a bulk {@code @Modifying} query runs immediately instead.
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
