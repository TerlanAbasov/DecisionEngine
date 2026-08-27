package com.quantplat.service;

import com.quantplat.data.MarketDataService;
import com.quantplat.domain.SignalEntity;
import com.quantplat.dto.Dtos.SignalDto;
import com.quantplat.execution.ExecutionEngineClient;
import com.quantplat.repository.SignalRepository;
import com.quantplat.strategy.BarSeries;
import com.quantplat.strategy.TradingStrategy;
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
    private final ExecutionEngineClient executionEngine;

    @Value("${quantplat.execution-engine.auto-forward:false}")
    private boolean autoForward;

    public ScannerService(MarketDataService marketData, StrategyService strategies,
                          UniverseService universe, SignalRepository signalRepo,
                          ExecutionEngineClient executionEngine) {
        this.marketData = marketData;
        this.strategies = strategies;
        this.universe = universe;
        this.signalRepo = signalRepo;
        this.executionEngine = executionEngine;
    }

    @Transactional
    public List<SignalDto> scan(List<String> symbols, boolean includeFlat) {
        List<String> syms = (symbols != null && !symbols.isEmpty())
                ? symbols.stream().map(String::toUpperCase).toList() : universe.get();
        Map<String, TradingStrategy> enabled = strategies.getEnabledStrategies();

        // cache bars per symbol
        Map<String, BarSeries> bars = new HashMap<>();
        for (String s : syms) bars.put(s, marketData.getBars(s, null, null));

        List<SignalDto> out = new ArrayList<>();
        List<SignalEntity> toSave = new ArrayList<>();
        for (Map.Entry<String, TradingStrategy> e : enabled.entrySet()) {
            TradingStrategy strat = e.getValue();
            Map<String, Double> params = strategies.getParams(e.getKey());
            for (String sym : syms) {
                BarSeries b = bars.get(sym);
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
                out.add(new SignalDto(e.getKey(), strat.category(), sym, label, isNew,
                        barsInState, round2(last), round2(b.close[n - 1]), asOf));

                SignalEntity se = new SignalEntity();
                se.setStrategyName(e.getKey());
                se.setSymbol(sym);
                se.setSignal(label);
                se.setWeight(round2(last));
                se.setBarsInState(barsInState);
                se.setNew(isNew);
                se.setClosePx(round2(b.close[n - 1]));
                se.setAsOfDate(asOf);
                toSave.add(se);
            }
        }
        signalRepo.saveAll(toSave);
        out.sort(Comparator.comparing(SignalDto::isNew).reversed()
                .thenComparing(SignalDto::strategy).thenComparing(SignalDto::symbol));

        if (autoForward && executionEngine.isConfigured()) forwardNewSignals(out);
        return out;
    }

    /** Best-effort forward of newly-flipped, non-FLAT signals; one failure doesn't stop the rest. */
    private void forwardNewSignals(List<SignalDto> signals) {
        for (SignalDto s : signals) {
            if (!s.isNew() || "FLAT".equals(s.signal())) continue;
            try {
                executionEngine.sendAlert(s);
            } catch (Exception e) {
                log.warn("Auto-forward to ExecutionEngine failed for {} {}: {}", s.strategy(), s.symbol(), e.getMessage());
            }
        }
    }

    public List<SignalDto> latest() {
        return signalRepo.findByOrderByCreatedAtDesc().stream()
                .map(s -> new SignalDto(s.getStrategyName(), strategies.categoryOf(s.getStrategyName()),
                        s.getSymbol(), s.getSignal(), s.isNew(), s.getBarsInState(),
                        s.getWeight(), s.getClosePx(), s.getAsOfDate()))
                .toList();
    }

    private static double clean(double v) { return Double.isNaN(v) ? 0 : v; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
