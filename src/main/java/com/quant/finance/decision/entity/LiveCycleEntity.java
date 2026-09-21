package com.quant.finance.decision.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One run of the job: what it looked at and did. */
@Entity
@Table(name = "live_cycle")
@Getter
@Setter
@NoArgsConstructor
public class LiveCycleEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant startedAt;
    private Instant finishedAt;

    @Column(nullable = false, length = 16)
    private String status;            // RUNNING | COMPLETED | SKIPPED | FAILED

    @Column(nullable = false, length = 12)
    private String mode;              // NORMAL | FLATTEN

    @Column(name = "triggered_by", nullable = false, length = 12)
    private String triggeredBy;       // SCHEDULED | MANUAL

    private boolean dryRun;

    @Column(columnDefinition = "text")
    private String message;

    private Integer symbols;
    private Integer strategies;
    private Integer signals;
    private Integer ordersPlanned;
    private Integer ordersFilled;
    private Integer ordersFailed;
    private Integer tradesClosed;
    private Integer errors;
    private Long durationMs;
}
