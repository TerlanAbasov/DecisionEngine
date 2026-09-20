package com.quant.finance.decision.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A closed virtual trade of one strategy in one symbol. */
@Entity
@Table(name = "live_trade")
@Getter
@Setter
@NoArgsConstructor
public class LiveTradeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String strategy;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false, length = 8)
    private String side;              // LONG | SHORT

    private double qty;
    private Instant entryTime;
    private double entryPrice;
    private Instant exitTime;
    private double exitPrice;
    private double pnlUsd;
    private double returnPct;

    @Column(nullable = false, length = 24)
    private String exitReason;        // SIGNAL | STOP_LOSS | TAKE_PROFIT | RESIZE | REMOVED | FLATTEN

    private Long cycleId;
}
