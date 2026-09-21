package com.quant.finance.decision.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "signal", indexes = {
        @Index(name = "ix_signal_strategy", columnList = "strategy_name"),
        @Index(name = "ix_signal_symbol", columnList = "symbol")})
@Getter
@Setter
@NoArgsConstructor
public class SignalEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_name", nullable = false)
    private String strategyName;

    private String symbol;
    private String signal;        // LONG | SHORT | FLAT
    private double weight;
    private int barsInState;
    private boolean isNew;
    private double closePx;
    private Instant asOfDate;
    private String timeframe;     // bar interval the signal was computed at (NATIVE | M15 | H1 | D1 | ...)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
