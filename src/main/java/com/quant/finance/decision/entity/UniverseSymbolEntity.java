package com.quant.finance.decision.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "universe_symbol",
        uniqueConstraints = @UniqueConstraint(name = "uk_universe_symbol", columnNames = "symbol"))
@Getter
@Setter
@NoArgsConstructor
public class UniverseSymbolEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    public UniverseSymbolEntity(String symbol) {
        this.symbol = symbol;
    }
}
