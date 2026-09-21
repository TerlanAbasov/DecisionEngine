package com.quant.finance.decision.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** The Alpaca account's equity at the end of a cycle. */
@Entity
@Table(name = "live_equity")
@Getter
@Setter
@NoArgsConstructor
public class LiveEquityEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant ts;
    private double equity;
    private Double cash;
    private Double buyingPower;
    private Double longValue;
    private Double shortValue;
    private Long cycleId;
}
