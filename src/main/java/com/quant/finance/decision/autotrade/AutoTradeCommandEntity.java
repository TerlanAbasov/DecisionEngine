package com.quant.finance.decision.autotrade;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One command the job decided to send for a strategy's change of position on one bar, and how it went. A command is written before it is sent, and
 * (strategy, symbol, timeframe, barOpen) is unique, so a bar can never trigger the same command twice, not even across a restart.
 */
@Entity
@Table(name = "auto_trade_command")
@Getter
@Setter
@NoArgsConstructor
public class AutoTradeCommandEntity {
    public static final int RESPONSE_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant createdAt;

    @Column(nullable = false)
    private String strategy;

    @Column(nullable = false, length = 16)
    private String symbol;

    @Column(nullable = false, length = 8)
    private String timeframe;

    /** Open time of the bar whose completion triggered the command. */
    @Column(nullable = false)
    private Instant barOpen;

    @Column(nullable = false, length = 8)
    private String side;                // BUY | SELL

    private double quantity;

    @Column(nullable = false, length = 8)
    private String orderType;

    private Double limitPrice;

    @Column(nullable = false, length = 12)
    private String status;              // PENDING | SENT | REJECTED | FAILED | SKIPPED

    @Column(length = RESPONSE_LENGTH)
    private String response;            // ExecutionEngine's reply, or why it was not sent
}
