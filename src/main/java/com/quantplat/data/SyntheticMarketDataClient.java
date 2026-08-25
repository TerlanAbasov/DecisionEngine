package com.quantplat.data;

import com.quantplat.domain.PriceBarEntity;
import com.quantplat.strategy.BarSeries;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Default data source: realistic offline bars, no external dependency. */
@Component
@ConditionalOnProperty(name = "quantplat.data-source", havingValue = "synthetic", matchIfMissing = true)
public class SyntheticMarketDataClient implements MarketDataClient {

    @Value("${quantplat.default-history-years:6.5}")
    private double years;

    @Override
    public List<PriceBarEntity> fetchHistory(String symbol) {
        BarSeries b = SyntheticData.generate(symbol, years, LocalDate.now());
        List<PriceBarEntity> out = new ArrayList<>(b.size());
        for (int i = 0; i < b.size(); i++)
            out.add(new PriceBarEntity(symbol, b.date[i], b.open[i], b.high[i],
                    b.low[i], b.close[i], b.volume[i], "synthetic"));
        return out;
    }

    @Override
    public String source() {
        return "synthetic";
    }
}
