package com.quant.finance.decision.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A background backtest: lifecycle, last progress snapshot and (once completed) the result shown in the UI. */
@Entity
@Table(name = "backtest_job")
@Getter
@Setter
@NoArgsConstructor
public class BacktestJobEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 16)
    private String kind;          // RUN | RUN_ALL | PAIRS | ENSEMBLE

    @Column(nullable = false, length = 16)
    private String status;        // RUNNING | COMPLETED | FAILED | CANCELLED

    @Column(length = 400)
    private String title;

    @Column(name = "request_json", columnDefinition = "text")
    private String requestJson;

    @Column(name = "progress_json", columnDefinition = "text")
    private String progressJson;

    @Column(name = "result_json", columnDefinition = "text")
    private String resultJson;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
