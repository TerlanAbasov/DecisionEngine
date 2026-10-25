package com.quant.finance.decision.data;

import com.quant.finance.decision.config.ApplicationProperties;
import com.quant.finance.decision.dto.Dtos.BarDto;
import com.quant.finance.decision.dto.Dtos.PriceSeriesDto;
import com.quant.finance.decision.dto.Dtos.SymbolCoverageDto;
import com.quant.finance.decision.engine.BarResampler;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.entity.PriceBarEntity;
import com.quant.finance.decision.repository.BarRow;
import com.quant.finance.decision.repository.PriceBarRepository;
import com.quant.finance.decision.strategy.BarSeries;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads bars from the DB cache, fetching from the active client on a miss. */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataService {

    private final PriceBarRepository repo;
    private final MarketDataClient client;
    private final ApplicationProperties properties;

    private boolean timeframeMatches(PriceBarEntity newest) {
        String want = client.configuredTimeframe();
        return newest != null
                && newest.getTimeframe() != null
                && newest.getTimeframe().equalsIgnoreCase(want);
    }

    /**
     * Cache coverage per symbol with stored bars: first/last bar, bar count and whether the newest bar is within the freshness window.
     * Lets the backtest form offer symbols that have current data.
     */
    @Transactional(readOnly = true)
    public List<SymbolCoverageDto> coverage() {
        Instant cutoff =
                Instant.now().minus(Duration.ofDays(properties.getData().getFreshDays()));
        return repo.findSymbolCoverage().stream()
                .map(r -> new SymbolCoverageDto(
                        r.getSymbol(),
                        r.getFirstBar(),
                        r.getLastBar(),
                        r.getBars(),
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
            log.info(
                    "MarketData: {} cached at '{}' but config is '{}' — refetching",
                    symbol,
                    newest.getTimeframe(),
                    client.configuredTimeframe());
            repo.deleteBySymbol(symbol);
            List<PriceBarEntity> bars = client.fetchHistory(symbol);
            repo.saveAll(bars);
            log.info("MarketData: {} recached {} bars @ {}", symbol, bars.size(), client.configuredTimeframe());
        }
    }

    /** Permanently drops a symbol's cached bars — no re-fetch. Re-adding it later starts from scratch. */
    @Transactional
    public int purge(String symbol) {
        int deletedCount = repo.deleteBySymbol(symbol);
        log.info("MarketData: purged {} — {} bars deleted", symbol, deletedCount);
        return deletedCount;
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
        List<BarRow> rows;
        if (start != null && end != null) {
            Instant from = start.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to =
                    end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
            rows = repo.findBarsBySymbolBetween(symbol, from, to);
        } else {
            rows = repo.findBarsBySymbol(symbol);
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
        String normalizedSymbol = symbol.trim().toUpperCase();
        Timeframe frame = Timeframe.from(timeframe);
        BarSeries bars = BarResampler.resample(getBars(normalizedSymbol, start, end), frame);
        int barCount = bars.size();
        int from = (limit > 0 && barCount > limit) ? barCount - limit : 0;
        List<BarDto> data = new ArrayList<>(Math.max(0, barCount - from));
        for (int i = from; i < barCount; i++) {
            data.add(new BarDto(
                    bars.date[i].toString(),
                    roundTo4Decimals(bars.open[i]),
                    roundTo4Decimals(bars.high[i]),
                    roundTo4Decimals(bars.low[i]),
                    roundTo4Decimals(bars.close[i]),
                    Math.round(bars.volume[i])));
        }
        Instant firstDate = barCount > from ? bars.date[from] : null;
        Instant lastDate = barCount > 0 ? bars.date[barCount - 1] : null;
        log.info(
                "MarketData: chart series {} @ {} -> {} bars ({} .. {})",
                normalizedSymbol,
                frame.name(),
                data.size(),
                firstDate,
                lastDate);
        return new PriceSeriesDto(normalizedSymbol, frame.name(), data.size(), firstDate, lastDate, data);
    }

    private static double roundTo4Decimals(double value) {
        return Math.round(value * 1e4) / 1e4;
    }

    private BarSeries toBarSeries(String symbol, List<BarRow> rows) {
        int rowCount = rows.size();
        Instant[] date = new Instant[rowCount];
        double[] open = new double[rowCount];
        double[] high = new double[rowCount];
        double[] low = new double[rowCount];
        double[] close = new double[rowCount];
        double[] volume = new double[rowCount];
        for (int i = 0; i < rowCount; i++) {
            BarRow row = rows.get(i);
            date[i] = row.barTime();
            open[i] = row.open();
            high[i] = row.high();
            low[i] = row.low();
            close[i] = row.close();
            volume[i] = row.volume();
        }
        return new BarSeries(symbol, date, open, high, low, close, volume);
    }
}
