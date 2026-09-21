package com.quant.finance.decision.autotrade;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** The single settings row of the auto-trading job (id is always 1). */
@Entity
@Table(name = "auto_trade_config")
@Getter
@Setter
@NoArgsConstructor
public class AutoTradeConfigEntity {
    @Id
    private Long id = 1L;

    private boolean enabled;
    private Instant enabledSince;
    private double quantity;

    @Column(name = "order_type", nullable = false, length = 8)
    private String orderType;

    @Column(nullable = false, length = 8)
    private String tif;

    @Column(columnDefinition = "text")
    private String strategyNames;

    @Column(columnDefinition = "text")
    private String symbols;

    private int maxCommandsPerRun;
    private Instant updatedAt;
}
