package com.quant.finance.decision.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A strategy's virtual P&L at the end of a cycle, for its curve. */
@Entity
@Table(name = "live_strategy_pnl")
@Getter
@Setter
@NoArgsConstructor
public class LiveStrategyPnlEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long cycleId;
    private Instant ts;

    @Column(nullable = false)
    private String strategy;

    private double realized;
    private double unrealized;
    private int openSlots;
}
