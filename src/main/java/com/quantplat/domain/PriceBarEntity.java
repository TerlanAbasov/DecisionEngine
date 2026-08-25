package com.quantplat.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "price_bar",
        uniqueConstraints = @UniqueConstraint(name = "uk_symbol_date", columnNames = {"symbol", "bar_date"}),
        indexes = @Index(name = "ix_price_symbol", columnList = "symbol"))
@Getter
@Setter
@NoArgsConstructor
public class PriceBarEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "bar_date", nullable = false)
    private LocalDate barDate;

    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;

    private String source;   // synthetic | ib

    public PriceBarEntity(String symbol, LocalDate barDate, double open, double high,
                          double low, double close, double volume, String source) {
        this.symbol = symbol;
        this.barDate = barDate;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.source = source;
    }
}
