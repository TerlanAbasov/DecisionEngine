package com.quantplat.data;

import com.quantplat.domain.PriceBarEntity;
import com.quantplat.repository.PriceBarRepository;
import com.quantplat.strategy.BarSeries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** Loads bars from the DB cache, fetching from the active client on a miss. */
@Service
public class MarketDataService {

    private final PriceBarRepository repo;
    private final MarketDataClient client;

    public MarketDataService(PriceBarRepository repo, MarketDataClient client) {
        this.repo = repo;
        this.client = client;
    }

    @Transactional
    public void ensureSymbol(String symbol) {
        if (!repo.existsBySymbol(symbol)) {
            repo.saveAll(client.fetchHistory(symbol));
        }
    }

    @Transactional
    public int refresh(String symbol) {
        repo.deleteBySymbol(symbol);
        List<PriceBarEntity> bars = client.fetchHistory(symbol);
        repo.saveAll(bars);
        return bars.size();
    }

    /**
     * Cheaper alternative to {@link #refresh} for periodic polling: re-fetches the
     * client's full history but only persists bars newer than what's cached, instead
     * of deleting and re-inserting everything on every poll tick.
     */
    @Transactional
    public int pollLatest(String symbol) {
        LocalDate cachedThrough = repo.findTopBySymbolOrderByBarDateDesc(symbol)
                .map(PriceBarEntity::getBarDate)
                .orElse(null);
        List<PriceBarEntity> bars = client.fetchHistory(symbol);
        List<PriceBarEntity> newBars = (cachedThrough == null) ? bars
                : bars.stream().filter(b -> b.getBarDate().isAfter(cachedThrough)).toList();
        repo.saveAll(newBars);
        return newBars.size();
    }

    @Transactional
    public BarSeries getBars(String symbol, LocalDate start, LocalDate end) {
        ensureSymbol(symbol);
        List<PriceBarEntity> rows = (start != null && end != null)
                ? repo.findBySymbolAndBarDateBetweenOrderByBarDate(symbol, start, end)
                : repo.findBySymbolOrderByBarDate(symbol);
        return toBarSeries(symbol, rows);
    }

    public String activeSource() {
        return client.source();
    }

    private BarSeries toBarSeries(String symbol, List<PriceBarEntity> rows) {
        int n = rows.size();
        LocalDate[] date = new LocalDate[n];
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n], v = new double[n];
        for (int i = 0; i < n; i++) {
            PriceBarEntity b = rows.get(i);
            date[i] = b.getBarDate();
            o[i] = b.getOpen(); h[i] = b.getHigh(); l[i] = b.getLow();
            c[i] = b.getClose(); v[i] = b.getVolume();
        }
        return new BarSeries(symbol, date, o, h, l, c, v);
    }
}
