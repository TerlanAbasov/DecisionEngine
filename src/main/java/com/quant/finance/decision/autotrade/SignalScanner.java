package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.config.ExecutorConfig;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.live.LiveDataSource;
import com.quant.finance.decision.live.LiveDataSource.Base;
import com.quant.finance.decision.live.SignalEvaluator;
import com.quant.finance.decision.live.SignalEvaluator.Reading;
import com.quant.finance.decision.service.StrategyService;
import com.quant.finance.decision.service.UniverseService;
import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Looks at each symbol's bars in each strategy's own timeframe, but only when a new bar is due (see {@link CheckSchedule}), and reports the strategies
 * that changed their mind on the bar that just completed. Every strategy sees only completed bars, as in the backtests.
 */
@Component
@Slf4j
class SignalScanner {

    /** Bars of history each strategy is evaluated on: enough for its indicators to warm up. */
    static final int LOOKBACK_BARS = 300;

    /** {@code checked}: (symbol, timeframe) pairs looked at; {@code errors}: pairs or strategies that could not be evaluated. */
    record Result(List<Flip> flips, int checked, int errors) {
        static final Result NONE = new Result(List.of(), 0, 0);

        static Result merge(List<Result> parts) {
            List<Flip> flips = new ArrayList<>();
            int checked = 0, errors = 0;
            for (Result part : parts) {
                flips.addAll(part.flips());
                checked += part.checked();
                errors += part.errors();
            }
            return new Result(flips, checked, errors);
        }
    }

    private final StrategyService strategies;
    private final UniverseService universe;
    private final LiveDataSource data;
    private final Executor executor;
    private final CheckSchedule schedule = new CheckSchedule();

    SignalScanner(StrategyService strategies, UniverseService universe, LiveDataSource data,
                  @Qualifier(ExecutorConfig.BACKTEST_EXECUTOR) Executor executor) {
        this.strategies = strategies;
        this.universe = universe;
        this.data = data;
        this.executor = executor;
    }

    Result scan(AutoTradeSettings settings, Instant now) {
        Map<Timeframe, List<TradingStrategy>> byFrame = new EnumMap<>(Timeframe.class);
        Map<String, Map<String, Double>> params = new HashMap<>();
        for (TradingStrategy strategy : settings.strategiesIn(strategies).values()) {
            Timeframe frame = SignalEvaluator.liveFrame(strategies.recommendedTimeframe(strategy.name()));
            byFrame.computeIfAbsent(frame, f -> new ArrayList<>()).add(strategy);
            params.put(strategy.name(), strategies.getParams(strategy.name()));
        }

        if (byFrame.isEmpty()) return Result.NONE;

        List<CompletableFuture<Result>> perSymbol = new ArrayList<>();
        for (String symbol : settings.symbolsIn(universe))
            perSymbol.add(CompletableFuture.supplyAsync(() -> scanSymbol(symbol, byFrame, params, settings.enabledSince(), now), executor));

        List<Result> results = new ArrayList<>();
        for (CompletableFuture<Result> future : perSymbol) {
            try {
                results.add(future.join());
            } catch (CompletionException e) {
                log.error("Auto-trading: a symbol could not be scanned", e.getCause());
                results.add(new Result(List.of(), 0, 1));
            }
        }
        return Result.merge(results);
    }

    private Result scanSymbol(String symbol, Map<Timeframe, List<TradingStrategy>> byFrame, Map<String, Map<String, Double>> params,
                              Instant enabledSince, Instant now) {
        List<Timeframe> due = byFrame.keySet().stream().filter(frame -> schedule.isDue(symbol, frame, now)).toList();
        if (due.isEmpty()) return Result.NONE;

        Map<Base, BarSeries> bars = fetchBars(symbol, due);
        List<Flip> flips = new ArrayList<>();
        int errors = 0;
        for (Timeframe frame : due) {
            BarSeries base = bars.get(SignalEvaluator.baseFor(frame));
            if (base == null) {
                errors++;
                schedule.checked(symbol, frame, null, now);
                continue;
            }
            Instant lastBar = null;
            for (TradingStrategy strategy : byFrame.get(frame)) {
                try {
                    Reading reading = SignalEvaluator.read(strategy, params.get(strategy.name()), base, frame, now);
                    if (reading == null) continue;                       // not enough history yet
                    lastBar = reading.barOpen();
                    Flip.detect(strategy.name(), symbol, frame, reading, enabledSince, now).ifPresent(flips::add);
                } catch (RuntimeException e) {
                    errors++;
                    log.warn("Auto-trading: {} on {} {} failed: {}", strategy.name(), symbol, frame, e.toString());
                }
            }
            schedule.checked(symbol, frame, lastBar, now);
        }
        return new Result(flips, due.size(), errors);
    }

    /** One fetch per base granularity, covering the longest lookback any due timeframe needs; a base that could not be fetched is missing. */
    private Map<Base, BarSeries> fetchBars(String symbol, List<Timeframe> due) {
        Map<Base, Integer> lookbackDays = new EnumMap<>(Base.class);
        for (Timeframe frame : due)
            lookbackDays.merge(SignalEvaluator.baseFor(frame), SignalEvaluator.lookbackDays(frame, LOOKBACK_BARS), Math::max);

        Map<Base, BarSeries> bars = new EnumMap<>(Base.class);
        lookbackDays.forEach((base, days) -> {
            try {
                bars.put(base, data.bars(symbol, base, days));
            } catch (RuntimeException e) {
                log.warn("Auto-trading: no {} bars for {}: {}", base, symbol, e.getMessage());
            }
        });
        return bars;
    }
}
