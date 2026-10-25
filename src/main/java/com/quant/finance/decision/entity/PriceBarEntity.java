package com.quant.finance.decision.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "price_bar",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_symbol_date",
                        columnNames = {"symbol", "bar_time"}),
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

    private String source; // synthetic | alpaca | ib

    /** Bar interval this row was fetched at ({@code application.alpaca.timeframe}: 1Day, 1Hour, 1Min, …). */
    private String timeframe;

    public PriceBarEntity(
            String symbol,
            Instant barTime,
            double open,
            double high,
            double low,
            double close,
            double volume,
            String source,
            String timeframe) {
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
