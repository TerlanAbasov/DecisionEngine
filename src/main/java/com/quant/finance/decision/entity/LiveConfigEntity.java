package com.quant.finance.decision.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** The single editable settings row of the paper-trading job (id is always 1). */
@Entity
@Table(name = "live_config")
@Getter
@Setter
@NoArgsConstructor
public class LiveConfigEntity {
    @Id
    private Long id = 1L;

    private boolean enabled;
    private boolean dryRun;
    private int intervalSeconds;
    private double allocationUsd;
    private double positionSize;
    private boolean allowShort;

    @Column(name = "timeframe_mode", nullable = false, length = 16)
    private String timeframeMode;

    private int lookbackBars;

    @Column(columnDefinition = "text")
    private String strategyNames;

    @Column(columnDefinition = "text")
    private String symbols;

    private double maxGrossUsd;
    private int maxOrdersPerCycle;
    private boolean marketHoursOnly;
    private int fillTimeoutSeconds;
    private boolean useRiskDefaults;
    private Instant updatedAt;
}
