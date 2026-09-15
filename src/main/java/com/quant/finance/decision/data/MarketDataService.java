package com.quant.finance.decision.data;

import com.quant.finance.decision.domain.PriceBarEntity;
import com.quant.finance.decision.dto.Dtos.BarDto;
import com.quant.finance.decision.dto.Dtos.PriceSeriesDto;
import com.quant.finance.decision.dto.Dtos.SymbolCoverageDto;
import com.quant.finance.decision.engine.BarResampler;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.repository.PriceBarRepository;
import com.quant.finance.decision.strategy.BarSeries;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Loads bars from the DB cache, fetching from the active client on a miss. */
@Service
@Slf4j
public class MarketDataService {

    private final PriceBarRepository repo;
    private final MarketDataClient client;
    private final long freshDays;

    public MarketDataService(PriceBarRepository repo, MarketDataClient client,
                             @Value("${decision.data.fresh-days:4}") long freshDays) {
        this.repo = repo;
        this.client = client;
        this.freshDays = freshDays;
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
            log.info("MarketData: {} not cached — fetching full history @ {}", symbol, client.configuredTimeframe());
            List<PriceBarEntity> bars = client.fetchHistory(symbol);
            repo.saveAll(bars);
            log.info("MarketData: {} cached {} bars", symbol, bars.size());
        } else if (!timeframeMatches(newest)) {
            // cached bars were fetched at a different interval (e.g. 1Day) than the one
            // now configured (e.g. 1Min) — wipe and refetch so the app stops serving stale bars
            log.info("MarketData: {} cached at '{}' but config is '{}' — refetching",
                    symbol, newest.getTimeframe(), client.configuredTimeframe());
            repo.deleteBySymbol(symbol);
            List<PriceBarEntity> bars = client.fetchHistory(symbol);
            repo.saveAll(bars);
            log.info("MarketData: {} recached {} bars @ {}", symbol, bars.size(), client.configuredTimeframe());
        }
    }

    @Transactional
    public int refresh(String symbol) {
        log.info("MarketData: refreshing {} (full re-fetch @ {})", symbol, client.configuredTimeframe());
        repo.deleteBySymbol(symbol);
        List<PriceBarEntity> bars = client.fetchHistory(symbol);
        repo.saveAll(bars);
        log.info("MarketData: refreshed {} — {} bars", symbol, bars.size());
        return bars.size();
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

    /**
     * OHLCV series for the chart view: cached bars (fetched on a miss) resampled up to
     * {@code timeframe} and trimmed to the most recent {@code limit} bars.
     */
    @Transactional
    public PriceSeriesDto priceSeries(String symbol, String timeframe, LocalDate start, LocalDate end, int limit) {
        String sym = symbol.trim().toUpperCase();
        Timeframe tf = Timeframe.from(timeframe);
        BarSeries b = BarResampler.resample(getBars(sym, start, end), tf);
        int n = b.size();
        int from = (limit > 0 && n > limit) ? n - limit : 0;
        List<BarDto> data = new ArrayList<>(Math.max(0, n - from));
        for (int i = from; i < n; i++)
            data.add(new BarDto(b.date[i].toString(),
                    r4(b.open[i]), r4(b.high[i]), r4(b.low[i]), r4(b.close[i]), Math.round(b.volume[i])));
        Instant s = n > from ? b.date[from] : null;
        Instant e = n > 0 ? b.date[n - 1] : null;
        log.info("MarketData: chart series {} @ {} -> {} bars ({} .. {})", sym, tf.name(), data.size(), s, e);
        return new PriceSeriesDto(sym, tf.name(), data.size(), s, e, data);
    }

    private static double r4(double v) { return Math.round(v * 1e4) / 1e4; }

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
