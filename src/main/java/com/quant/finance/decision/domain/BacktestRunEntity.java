package com.quant.finance.decision.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "backtest_run", indexes = @Index(name = "ix_run_strategy", columnList = "strategy_name"))
@Getter
@Setter
@NoArgsConstructor
public class BacktestRunEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_name", nullable = false)
    private String strategyName;

    @Column(columnDefinition = "text")
    private String symbolsCsv;

    private Instant startDate;
    private Instant endDate;

    private double capital;
    private double commissionBps;
    private double slippageBps;
    private boolean allowShort;

    // execution / risk controls (nullable — older rows predate them)
    private String timeframe;          // NATIVE | H1 | H4 | D1 | W1 | MN
    private Integer execLag;
    private Double positionSize;
    private Double riskFreePct;
    private Integer warmupBars;
    private Double stopLossPct;
    private Double takeProfitPct;
    private Integer bars;              // bar count actually backtested (post-resample)

    // headline result metrics, denormalised from BacktestResult.metricsJson so run
    // history can be sorted / filtered in SQL (nullable — backfilled for older rows)
    private Double totalReturnPct;
    private Double cagrPct;
    private Double sharpe;
    private Double sortino;
    private Double calmar;
    private Double maxDrawdownPct;
    private Double annVolPct;
    private Double winRatePct;
    private Double profitFactor;
    private Double exposurePct;
    private Integer trades;

    private String status;     // COMPLETED | FAILED
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
