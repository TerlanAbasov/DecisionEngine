package com.quantplat.data;

import com.quantplat.domain.PriceBarEntity;
import java.util.List;

/** Source of historical daily bars. One implementation is active per profile. */
public interface MarketDataClient {
    List<PriceBarEntity> fetchHistory(String symbol);
    String source();
}
