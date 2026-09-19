package com.quant.finance.decision.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "backtest_result")
@Getter
@Setter
@NoArgsConstructor
public class BacktestResultEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false, unique = true)
    private BacktestRunEntity run;

    /** JSON: {"sharpe":1.2,...} */
    @Column(columnDefinition = "text")
    private String metricsJson;

    /** JSON arrays serialised as text. */
    @Column(columnDefinition = "text")
    private String datesJson;
    @Column(columnDefinition = "text")
    private String equityJson;
    @Column(columnDefinition = "text")
    private String benchmarkJson;
    @Column(columnDefinition = "text")
    private String drawdownJson;

    /** JSON: {"NVDA":12.3,"TSLA":-4.5,...} — each symbol's own total return %. */
    @Column(columnDefinition = "text")
    private String symbolReturnsJson;

    /** JSON {@code SymbolDetails}: per-symbol metrics + calendar-year returns, and the blend's yearly returns. */
    @Column(columnDefinition = "text")
    private String symbolDetailsJson;
}
