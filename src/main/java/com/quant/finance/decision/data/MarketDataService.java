package com.quant.finance.decision.data;

import com.quant.finance.decision.domain.PriceBarEntity;
import com.quant.finance.decision.dto.Dtos.BarDto;
import com.quant.finance.decision.dto.Dtos.PriceSeriesDto;
import com.quant.finance.decision.dto.Dtos.SymbolCoverageDto;
import com.quant.finance.decision.engine.BarResampler;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.repository.PriceBarRepository;
import com.quant.finance.decision.strategy.BarSeries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final PriceBarRepository repo;
    private final MarketDataClient client;
    private final long freshDays;
    private final long pollLookbackDays;

    public MarketDataService(PriceBarRepository repo, MarketDataClient client,
                             @Value("${decision.data.fresh-days:4}") long freshDays,
                             @Value("${decision.poll.lookback-days:2}") long pollLookbackDays) {
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
            log.info("MarketData: poll {} — first fetch, full history @ {}", symbol, client.configuredTimeframe());
            List<PriceBarEntity> bars = client.fetchHistory(symbol);
            repo.saveAll(bars);
            log.info("MarketData: poll {} — cached {} bars", symbol, bars.size());
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
        if (!newBars.isEmpty())
            log.info("MarketData: poll {} — +{} new bars (through {})", symbol, newBars.size(),
                    newBars.get(newBars.size() - 1).getBarTime());
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
