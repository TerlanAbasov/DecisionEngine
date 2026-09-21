package com.quant.finance.decision.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "price_bar",
        uniqueConstraints = @UniqueConstraint(name = "uk_symbol_date", columnNames = {"symbol", "bar_time"}),
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

    /** Bar open time (UTC). One row per bar at whatever granularity was fetched (daily, hourly, ...). */
    @Column(name = "bar_time", nullable = false)
    private Instant barTime;

    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;

    private String source;   // synthetic | alpaca | ib

    /** Bar interval this row was fetched at ({@code decision.alpaca.timeframe}: 1Day, 1Hour, 1Min, …). */
    private String timeframe;

    public PriceBarEntity(String symbol, Instant barTime, double open, double high,
                          double low, double close, double volume, String source, String timeframe) {
        this.symbol = symbol;
        this.barTime = barTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.source = source;
        this.timeframe = timeframe;
    }
}
