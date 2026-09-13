package com.quant.finance.decision.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "trade", indexes = @Index(name = "ix_trade_run", columnList = "run_id"))
@Getter
@Setter
@NoArgsConstructor
public class TradeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private BacktestRunEntity run;

    private String symbol;
    private String side;
    private Instant entryDate;
    private Instant exitDate;
    private double entryPx;
    private double exitPx;
    private int bars;
    private double returnPct;
}
