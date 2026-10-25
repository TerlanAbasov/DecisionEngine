package com.quant.finance.decision.service;

import com.quant.finance.decision.data.MarketDataService;
import com.quant.finance.decision.dto.Dtos.SignalDto;
import com.quant.finance.decision.dto.Dtos.SignalMarkerDto;
import com.quant.finance.decision.dto.Dtos.SignalOverlayDto;
import com.quant.finance.decision.dto.Dtos.StrategySignalsDto;
import com.quant.finance.decision.engine.BarResampler;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.entity.SignalEntity;
import com.quant.finance.decision.repository.SignalRepository;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScannerService {

    private final MarketDataService marketData;
    private final StrategyService strategies;
    private final UniverseService universe;
    private final SignalRepository signalRepo;

    @Transactional
    public List<SignalDto> scan(List<String> symbols, boolean includeFlat) {
        return scan(symbols, includeFlat, null);
    }

    @Transactional
    public List<SignalDto> scan(List<String> symbols, boolean includeFlat, String timeframe) {
        List<String> symbolList = (symbols != null && !symbols.isEmpty())
                ? symbols.stream().map(String::toUpperCase).toList()
                : universe.get();
        Map<String, TradingStrategy> enabled = strategies.getEnabledStrategies();
        long startMillis = System.currentTimeMillis();
        log.info(
                "Scan: {} enabled strategies x {} symbols @ {}",
                enabled.size(),
                symbolList.size(),
                (timeframe != null && !Timeframe.isAuto(timeframe)) ? timeframe : "per-strategy timeframe");

        Map<String, BarSeries> nativeBars = new HashMap<>();
        for (String symbol : symbolList) {
            nativeBars.put(symbol, marketData.getBars(symbol, null, null));
        }

        boolean forced = timeframe != null && !Timeframe.isAuto(timeframe);
        Timeframe forcedFrame = forced ? Timeframe.from(timeframe) : null;
        Map<String, BarSeries> resampled = new HashMap<>(); // key: symbol|TIMEFRAME

        List<SignalDto> out = new ArrayList<>();
        List<SignalEntity> toSave = new ArrayList<>();
        for (Map.Entry<String, TradingStrategy> entry : enabled.entrySet()) {
            String name = entry.getKey();
            TradingStrategy strategy = entry.getValue();
            Map<String, Double> params = strategies.getParams(name);
            Timeframe frame = forced ? forcedFrame : strategies.recommendedTimeframe(name);
            for (String symbol : symbolList) {
                BarSeries nativeSeries = nativeBars.get(symbol);
                if (nativeSeries == null || nativeSeries.size() < 2) {
                    continue;
                }
                BarSeries bars = resampled.computeIfAbsent(
                        symbol + "|" + frame.name(), k -> BarResampler.resample(nativeSeries, frame));
                if (bars == null || bars.size() < 2) {
                    continue;
                }
                double[] signal = strategy.generateSignals(bars, params.isEmpty() ? null : params);
                int length = signal.length;
                double last = nanToZero(signal[length - 1]);
                double prev = nanToZero(signal[length - 2]);
                String label = last > 0 ? "LONG" : last < 0 ? "SHORT" : "FLAT";
                boolean isNew = Math.signum(last) != Math.signum(prev);
                if (!includeFlat && label.equals("FLAT") && !isNew) {
                    continue;
                }
                int barsInState = 1;
                for (int i = length - 2; i >= 0; i--) {
                    if (Math.signum(nanToZero(signal[i])) == Math.signum(last)) {
                        barsInState++;
                    } else {
                        break;
                    }
                }
                Instant asOf = bars.date[length - 1];
                out.add(new SignalDto(
                        name,
                        strategy.category(),
                        symbol,
                        label,
                        isNew,
                        barsInState,
                        roundTo2Decimals(last),
                        roundTo2Decimals(bars.close[length - 1]),
                        asOf,
                        frame.name()));

                SignalEntity se = new SignalEntity();
                se.setStrategyName(name);
                se.setSymbol(symbol);
                se.setSignal(label);
                se.setWeight(roundTo2Decimals(last));
                se.setBarsInState(barsInState);
                se.setNew(isNew);
                se.setClosePx(roundTo2Decimals(bars.close[length - 1]));
                se.setAsOfDate(asOf);
                se.setTimeframe(frame.name());
                toSave.add(se);
            }
        }
        signalRepo.saveAll(toSave);
        long newN = out.stream().filter(SignalDto::isNew).count();
        log.info(
                "Scan: done in {} ms — {} signals ({} newly flipped)",
                System.currentTimeMillis() - startMillis,
                out.size(),
                newN);
        out.sort(Comparator.comparing(SignalDto::isNew)
                .reversed()
                .thenComparing(SignalDto::strategy)
                .thenComparing(SignalDto::symbol));

        return out;
    }

    public List<SignalDto> latest() {
        return signalRepo.findByOrderByCreatedAtDesc().stream()
                .map(s -> new SignalDto(
                        s.getStrategyName(),
                        strategies.categoryOf(s.getStrategyName()),
                        s.getSymbol(),
                        s.getSignal(),
                        s.isNew(),
                        s.getBarsInState(),
                        s.getWeight(),
                        s.getClosePx(),
                        s.getAsOfDate(),
                        s.getTimeframe() != null ? s.getTimeframe() : Timeframe.NATIVE.name()))
                .toList();
    }

    private static double nanToZero(double value) {
        return Double.isNaN(value) ? 0 : value;
    }

    private static double roundTo2Decimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static final int MAX_MARKERS = 1000;

    @Transactional
    public SignalOverlayDto chartSignals(String symbol, String timeframe, List<String> names, int limit) {
        String normalizedSymbol = symbol.trim().toUpperCase();
        BarSeries nativeSeries = marketData.getBars(normalizedSymbol, null, null);
        boolean forced = timeframe != null && !Timeframe.isAuto(timeframe);
        Timeframe forcedFrame = forced ? Timeframe.from(timeframe) : null;
        Map<String, BarSeries> resampleCache = new HashMap<>();

        List<StrategySignalsDto> out = new ArrayList<>();
        for (String name : names == null ? List.<String>of() : names) {
            if (name == null || name.isBlank() || !strategies.isStrategy(name)) {
                continue;
            }
            TradingStrategy strategy = strategies.getStrategy(name);
            Map<String, Double> params = strategies.getParams(name);
            Timeframe frame = forced ? forcedFrame : strategies.recommendedTimeframe(name);
            BarSeries bars =
                    resampleCache.computeIfAbsent(frame.name(), k -> BarResampler.resample(nativeSeries, frame));
            if (bars.size() < 2) {
                out.add(new StrategySignalsDto(name, frame.name(), List.of()));
                continue;
            }

            double[] signal = strategy.generateSignals(bars, params.isEmpty() ? null : params);
            int length = bars.size();
            int from = (limit > 0 && length > limit) ? length - limit : 0;
            List<SignalMarkerDto> markers = new ArrayList<>();
            double prev = from > 0 ? Math.signum(nanToZero(signal[from - 1])) : 0;
            for (int i = from; i < length; i++) {
                double cur = Math.signum(nanToZero(signal[i]));
                if (cur != prev) {
                    String type = cur > 0 ? "BUY" : cur < 0 ? "SELL" : "EXIT";
                    markers.add(
                            new SignalMarkerDto(bars.date[i].toString(), roundTo2Decimals(bars.close[i]), type, cur));
                }
                prev = cur;
            }
            if (markers.size() > MAX_MARKERS) {
                markers = new ArrayList<>(markers.subList(markers.size() - MAX_MARKERS, markers.size()));
            }
            out.add(new StrategySignalsDto(name, frame.name(), markers));
        }
        int total = out.stream().mapToInt(s -> s.markers().size()).sum();
        log.info(
                "Chart signals: {} on {} strategy(ies) @ {} -> {} markers",
                normalizedSymbol,
                out.size(),
                forced ? forcedFrame : "per-strategy",
                total);
        return new SignalOverlayDto(normalizedSymbol, out);
    }
}
