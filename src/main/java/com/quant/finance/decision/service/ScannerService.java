package com.quant.finance.decision.service;

import com.quant.finance.decision.data.MarketDataService;
import com.quant.finance.decision.domain.SignalEntity;
import com.quant.finance.decision.dto.Dtos.SignalDto;
import com.quant.finance.decision.dto.Dtos.SignalMarkerDto;
import com.quant.finance.decision.dto.Dtos.SignalOverlayDto;
import com.quant.finance.decision.dto.Dtos.StrategySignalsDto;
import com.quant.finance.decision.engine.BarResampler;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.repository.SignalRepository;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class ScannerService {

    private static final Logger log = LoggerFactory.getLogger(ScannerService.class);

    private final MarketDataService marketData;
    private final StrategyService strategies;
    private final UniverseService universe;
    private final SignalRepository signalRepo;
    private final CommandService executionEngine;

    /** Forward new LONG/SHORT signals to ExecutionEngine's TradeController
     *  (POST /api/v1/trades/command) as they're detected. */
    @Value("${quantplat.execution-engine.auto-forward:false}")
    private boolean autoForward;

    /** Blank = no restriction (every enabled strategy's new signals are forwarded). */
    @Value("${quantplat.execution-engine.strategies:}")
    private String forwardStrategiesCsv;

    public ScannerService(MarketDataService marketData, StrategyService strategies,
                          UniverseService universe, SignalRepository signalRepo,
                          CommandService executionEngine) {
        this.marketData = marketData;
        this.strategies = strategies;
        this.universe = universe;
        this.signalRepo = signalRepo;
        this.executionEngine = executionEngine;
    }

    @Transactional
    public List<SignalDto> scan(List<String> symbols, boolean includeFlat) {
        return scan(symbols, includeFlat, null);
    }

    /**
     * Generate live signals. Each strategy is run on bars resampled up to its own
     * {@code recommendedTimeframe} (so signals match how it was backtested) — unless
     * {@code timeframe} pins every strategy to one explicit frame. Blank / "AUTO" =
     * per-strategy. Source bars (e.g. 1-minute) are loaded once and resampled per frame.
     */
    @Transactional
    public List<SignalDto> scan(List<String> symbols, boolean includeFlat, String timeframe) {
        List<String> syms = (symbols != null && !symbols.isEmpty())
                ? symbols.stream().map(String::toUpperCase).toList() : universe.get();
        Map<String, TradingStrategy> enabled = strategies.getEnabledStrategies();
        long t0 = System.currentTimeMillis();
        log.info("Scan: {} enabled strategies x {} symbols @ {}", enabled.size(), syms.size(),
                (timeframe != null && !Timeframe.isAuto(timeframe)) ? timeframe : "per-strategy timeframe");

        Map<String, BarSeries> nativeBars = new HashMap<>();
        for (String s : syms) nativeBars.put(s, marketData.getBars(s, null, null));

        boolean forced = timeframe != null && !Timeframe.isAuto(timeframe);
        Timeframe forcedTf = forced ? Timeframe.from(timeframe) : null;
        Map<String, BarSeries> resampled = new HashMap<>();   // key: symbol|TIMEFRAME

        List<SignalDto> out = new ArrayList<>();
        List<SignalEntity> toSave = new ArrayList<>();
        for (Map.Entry<String, TradingStrategy> e : enabled.entrySet()) {
            String name = e.getKey();
            TradingStrategy strat = e.getValue();
            Map<String, Double> params = strategies.getParams(name);
            Timeframe tf = forced ? forcedTf : strategies.recommendedTimeframe(name);
            for (String sym : syms) {
                BarSeries nb = nativeBars.get(sym);
                if (nb == null || nb.size() < 2) continue;
                BarSeries b = resampled.computeIfAbsent(sym + "|" + tf.name(),
                        k -> BarResampler.resample(nb, tf));
                if (b == null || b.size() < 2) continue;
                double[] sig = strat.generateSignals(b, params.isEmpty() ? null : params);
                int n = sig.length;
                double last = clean(sig[n - 1]);
                double prev = clean(sig[n - 2]);
                String label = last > 0 ? "LONG" : last < 0 ? "SHORT" : "FLAT";
                boolean isNew = Math.signum(last) != Math.signum(prev);
                if (!includeFlat && label.equals("FLAT") && !isNew) continue;
                int barsInState = 1;
                for (int i = n - 2; i >= 0; i--) {
                    if (Math.signum(clean(sig[i])) == Math.signum(last)) barsInState++;
                    else break;
                }
                Instant asOf = b.date[n - 1];
                out.add(new SignalDto(name, strat.category(), sym, label, isNew,
                        barsInState, round2(last), round2(b.close[n - 1]), asOf, tf.name()));

                SignalEntity se = new SignalEntity();
                se.setStrategyName(name);
                se.setSymbol(sym);
                se.setSignal(label);
                se.setWeight(round2(last));
                se.setBarsInState(barsInState);
                se.setNew(isNew);
                se.setClosePx(round2(b.close[n - 1]));
                se.setAsOfDate(asOf);
                se.setTimeframe(tf.name());
                toSave.add(se);
            }
        }
        signalRepo.saveAll(toSave);
        long newN = out.stream().filter(SignalDto::isNew).count();
        log.info("Scan: done in {} ms — {} signals ({} newly flipped)", System.currentTimeMillis() - t0, out.size(), newN);
        out.sort(Comparator.comparing(SignalDto::isNew).reversed()
                .thenComparing(SignalDto::strategy).thenComparing(SignalDto::symbol));

        if (autoForward && executionEngine.isConfigured()) forwardNewSignals(out);
        return out;
    }

    /** Best-effort forward of newly-flipped, non-FLAT signals to ExecutionEngine's
     *  TradeController; one failure doesn't stop the rest. */
    private void forwardNewSignals(List<SignalDto> signals) {
        Set<String> allow = forwardStrategiesCsv == null || forwardStrategiesCsv.isBlank()
                ? null
                : Arrays.stream(forwardStrategiesCsv.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());

        for (SignalDto s : signals) {
            if (!s.isNew() || "FLAT".equals(s.signal())) continue;
            if (allow != null && !allow.contains(s.strategy())) continue;
            try {
                executionEngine.sendTradeCommand(s);
            } catch (Exception e) {
                log.warn("Auto-forward to ExecutionEngine failed for {} {}: {}",
                        s.strategy(), s.symbol(), e.getMessage());
            }
        }
    }

    public List<SignalDto> latest() {
        return signalRepo.findByOrderByCreatedAtDesc().stream()
                .map(s -> new SignalDto(s.getStrategyName(), strategies.categoryOf(s.getStrategyName()),
                        s.getSymbol(), s.getSignal(), s.isNew(), s.getBarsInState(),
                        s.getWeight(), s.getClosePx(), s.getAsOfDate(),
                        s.getTimeframe() != null ? s.getTimeframe() : Timeframe.NATIVE.name()))
                .toList();
    }

    private static double clean(double v) { return Double.isNaN(v) ? 0 : v; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static final int MAX_MARKERS = 1000;

    /**
     * Per-bar position-change markers for one symbol, for the chart overlay. Each strategy runs
     * on the given {@code timeframe} (blank / "AUTO" = its own recommended frame). A marker is
     * emitted where the target position's sign changes: BUY (→ long), SELL (→ short), EXIT (→ flat).
     */
    @Transactional
    public SignalOverlayDto chartSignals(String symbol, String timeframe, List<String> names, int limit) {
        String sym = symbol.trim().toUpperCase();
        BarSeries nb = marketData.getBars(sym, null, null);
        boolean forced = timeframe != null && !Timeframe.isAuto(timeframe);
        Timeframe forcedTf = forced ? Timeframe.from(timeframe) : null;
        Map<String, BarSeries> resampleCache = new HashMap<>();

        List<StrategySignalsDto> out = new ArrayList<>();
        for (String name : names == null ? List.<String>of() : names) {
            if (name == null || name.isBlank() || !strategies.isStrategy(name)) continue;
            TradingStrategy strat = strategies.getStrategy(name);
            Map<String, Double> params = strategies.getParams(name);
            Timeframe tf = forced ? forcedTf : strategies.recommendedTimeframe(name);
            BarSeries b = resampleCache.computeIfAbsent(tf.name(), k -> BarResampler.resample(nb, tf));
            if (b.size() < 2) { out.add(new StrategySignalsDto(name, tf.name(), List.of())); continue; }

            double[] sig = strat.generateSignals(b, params.isEmpty() ? null : params);
            int n = b.size();
            int from = (limit > 0 && n > limit) ? n - limit : 0;
            List<SignalMarkerDto> markers = new ArrayList<>();
            double prev = from > 0 ? Math.signum(clean(sig[from - 1])) : 0;
            for (int i = from; i < n; i++) {
                double cur = Math.signum(clean(sig[i]));
                if (cur != prev) {
                    String type = cur > 0 ? "BUY" : cur < 0 ? "SELL" : "EXIT";
                    markers.add(new SignalMarkerDto(b.date[i].toString(), round2(b.close[i]), type, cur));
                }
                prev = cur;
            }
            if (markers.size() > MAX_MARKERS)
                markers = new ArrayList<>(markers.subList(markers.size() - MAX_MARKERS, markers.size()));
            out.add(new StrategySignalsDto(name, tf.name(), markers));
        }
        int total = out.stream().mapToInt(s -> s.markers().size()).sum();
        log.info("Chart signals: {} on {} strategy(ies) @ {} -> {} markers", sym, out.size(),
                forced ? forcedTf : "per-strategy", total);
        return new SignalOverlayDto(sym, out);
    }
}
