package com.quantplat.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "strategy_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_strategy_name", columnNames = "name"))
@Getter
@Setter
@NoArgsConstructor
public class StrategyConfigEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String category;
    private String direction;

    @Column(length = 512)
    private String description;

    @Column(nullable = false)
    private boolean enabled = true;

    /** JSON map of parameter overrides, e.g. {"fast":20,"slow":100}. Null => defaults. */
    @Column(columnDefinition = "text")
    private String paramsJson;

    // ---- per-strategy behaviour controls (nullable => sensible default) ----

    /** Blend weight when strategies are combined into an ensemble. Null => 1.0. */
    private Double weight;

    /** Trade the strategy contrarian: every target position is negated. Null/false => normal. */
    private Boolean invert;

    /** Force a stance regardless of the strategy's native one: long_only | short_only | long_short. */
    private String directionOverride;

    /** Free-form comma-separated labels for filtering (e.g. "core,intraday,fx"). */
    @Column(length = 512)
    private String tags;

    /** Pinned by the user in the UI. */
    private Boolean favorite;

    @Column(length = 1000)
    private String notes;

    // ---- day-trading metadata (nullable => per-category default in StrategyService) ----

    /** Bar interval this strategy is tuned for, as a {@code Timeframe} name (M5, M15, H1, D1, …). */
    private String recommendedTimeframe;

    /** Whether the strategy is appropriate for intraday day-trading (false e.g. for seasonal effects). */
    private Boolean intraday;
}
