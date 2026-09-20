package com.quant.finance.decision.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** An order the job sent (or, in dry-run, would have sent) to Alpaca: one net order per symbol per cycle. */
@Entity
@Table(name = "live_order")
@Getter
@Setter
@NoArgsConstructor
public class LiveOrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long cycleId;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false, length = 8)
    private String side;              // BUY | SELL

    private double qty;

    @Column(nullable = false, length = 24)
    private String status;            // DRY_RUN | FILLED | PARTIAL | CANCELED | REJECTED | FAILED | SKIPPED

    @Column(length = 64)
    private String alpacaOrderId;

    @Column(length = 80)
    private String clientOrderId;

    private Double filledQty;
    private Double filledAvgPrice;
    private Instant submittedAt;
    private Instant filledAt;

    @Column(columnDefinition = "text")
    private String error;

    private boolean dryRun;

    @Column(columnDefinition = "text")
    private String reason;

    private Double targetQty;         // the whole-share position the strategies together asked for
    private Double positionBefore;    // the account's position before this order
}
