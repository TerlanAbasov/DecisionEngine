package com.quantplat.data;

import com.quantplat.domain.PriceBarEntity;
import com.quantplat.dto.Dtos.SymbolCoverageDto;
import com.quantplat.repository.PriceBarRepository;
import com.quantplat.strategy.BarSeries;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** Loads bars from the DB cache, fetching from the active client on a miss. */
@Service
public class MarketDataService {

    private final PriceBarRepository repo;
    private final MarketDataClient client;
    private final long freshDays;
    private final long pollLookbackDays;

    public MarketDataService(PriceBarRepository repo, MarketDataClient client,
                             @Value("${quantplat.data.fresh-days:4}") long freshDays,
                             @Value("${quantplat.poll.lookback-days:2}") long pollLookbackDays) {
        this.repo = repo;
        this.client = client;
        this.freshDays = freshDays;
        this.pollLookbackDays = Math.max(1, pollLookbackDays);
    }

    private boolean timeframeMatches(PriceBarEntity newest) {
        String want = client.configuredTimeframe();
        return newest != null && newest.getTimeframe() != null
                && newest.getTimeframe().equalsIgnoreCase(want);
    }

    /**
     * Cache coverage for every symbol that has bars stored locally — first/last bar,
     * bar count and whether the newest bar is within the freshness window. Used by the
     * backtest form to let the user pick from symbols that actually have current data.
     */
    @Transactional(readOnly = true)
    public List<SymbolCoverageDto> coverage() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(freshDays));
        return repo.findSymbolCoverage().stream()
                .map(r -> new SymbolCoverageDto(
                        r.getSymbol(), r.getFirstBar(), r.getLastBar(), r.getBars(),
                        r.getLastBar() != null && r.getLastBar().isAfter(cutoff),
                        r.getTimeframe()))
                .toList();
    }

    @Transactional
    public void ensureSymbol(String symbol) {
        PriceBarEntity newest = repo.findTopBySymbolOrderByBarTimeDesc(symbol).orElse(null);
        if (newest == null) {
            repo.saveAll(client.fetchHistory(symbol));
        } else if (!timeframeMatches(newest)) {
            // cached bars were fetched at a different interval (e.g. 1Day) than the one
            // now configured (e.g. 1Min) — wipe and refetch so the app stops serving stale bars
            repo.deleteBySymbol(symbol);
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
        PriceBarEntity newest = repo.findTopBySymbolOrderByBarTimeDesc(symbol).orElse(null);
        // if the cache holds a different interval, appending would splice two granularities
        // into one series — do a full refresh instead
        if (newest != null && !timeframeMatches(newest)) return refresh(symbol);

        if (newest == null) {                    // first fetch — pull the full history
            List<PriceBarEntity> bars = client.fetchHistory(symbol);
            repo.saveAll(bars);
            return bars.size();
        }

        // incremental: only ask Alpaca for bars since the last cached one (minus a small
        // look-back so a gap from a missed poll or a still-forming bar is picked up), instead
        // of re-downloading years of history every tick
        Instant cachedThrough = newest.getBarTime();
        Instant since = cachedThrough.minus(Duration.ofDays(pollLookbackDays));
        List<PriceBarEntity> bars = client.fetchHistory(symbol, since);
        List<PriceBarEntity> newBars = bars.stream()
                .filter(b -> b.getBarTime().isAfter(cachedThrough))
                .toList();
        repo.saveAll(newBars);
        return newBars.size();
    }

    @Transactional
    public BarSeries getBars(String symbol, LocalDate start, LocalDate end) {
        ensureSymbol(symbol);
        List<PriceBarEntity> rows;
        if (start != null && end != null) {
            Instant from = start.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
            rows = repo.findBySymbolAndBarTimeBetweenOrderByBarTime(symbol, from, to);
        } else {
            rows = repo.findBySymbolOrderByBarTime(symbol);
        }
        return toBarSeries(symbol, rows);
    }

    public String activeSource() {
        return client.source();
    }

    private BarSeries toBarSeries(String symbol, List<PriceBarEntity> rows) {
        int n = rows.size();
        Instant[] date = new Instant[n];
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n], v = new double[n];
        for (int i = 0; i < n; i++) {
            PriceBarEntity b = rows.get(i);
            date[i] = b.getBarTime();
            o[i] = b.getOpen(); h[i] = b.getHigh(); l[i] = b.getLow();
            c[i] = b.getClose(); v[i] = b.getVolume();
        }
        return new BarSeries(symbol, date, o, h, l, c, v);
    }
}
