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
}
