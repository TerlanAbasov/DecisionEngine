package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.strategy.BarSeries;

import java.util.Collection;
import java.util.Map;

/** Fresh market data for auto trading's signal detection. Implemented over Alpaca; faked in tests. */
public interface LiveDataSource {

    /** Granularity the bars are fetched at; coarser frames are built from these with the backtest's resampler. */
    enum Base { MIN1("1Min"), DAY1("1Day");
        public final String alpacaTimeframe;
        Base(String alpacaTimeframe) { this.alpacaTimeframe = alpacaTimeframe; }
    }

    /**
     * The symbol's bars at {@code base}, oldest first, reaching back at least {@code lookbackDays} calendar days.
     * The newest bar may still be forming — callers drop it. May be empty (no data), never null.
     */
    BarSeries bars(String symbol, Base base, int lookbackDays);

    /** Latest traded price per symbol; symbols without a price are left out. */
    Map<String, Double> latestPrices(Collection<String> symbols);
}
