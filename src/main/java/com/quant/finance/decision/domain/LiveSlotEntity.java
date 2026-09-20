package com.quant.finance.decision.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** The virtual position of one strategy in one symbol. Alpaca nets positions per symbol, so this is where each strategy's own position and P&L live. */
@Entity
@Table(name = "live_slot")
@Getter
@Setter
@NoArgsConstructor
public class LiveSlotEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String strategy;

    @Column(nullable = false, length = 32)
    private String symbol;

    private int direction;            // -1 short, 0 flat, 1 long
    private double qty;               // virtual shares held (>= 0); sign is in direction
    private Double entryPrice;
    private Instant entryTime;
    private int blockedDir;           // direction a stop-loss / take-profit closed, until the strategy stops asking for it
    private Double lastSignal;
    private Instant lastSignalAt;
    private Double lastPrice;
    private double realizedPnl;
    private Instant updatedAt;
}
