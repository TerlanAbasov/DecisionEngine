package com.quantplat.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Request/response payloads for the REST API. */
public final class Dtos {
    private Dtos() {}

    /**
     * {@code direction} is the effective stance (after any override); {@code nativeDirection} is
     * the strategy's own. {@code params} are the effective params (overrides merged onto defaults),
     * {@code defaultParams} the catalog defaults, {@code overridden} the keys the user changed.
     */
    public record StrategyDto(String name, String category, String direction, String nativeDirection,
                              String description, boolean enabled, double weight, boolean invert,
                              String directionOverride, List<String> tags, boolean favorite, String notes,
                              String recommendedTimeframe, boolean intraday,
                              Map<String, Double> params, Map<String, Double> defaultParams,
                              List<String> overridden) {}

    /** Partial update of a strategy's behaviour controls — any null field is left unchanged. */
    public record StrategyControlsUpdate(Double weight, Boolean invert, String directionOverride,
                                         List<String> tags, Boolean favorite, String notes,
                                         String recommendedTimeframe, Boolean intraday) {}

    /** One cell of a parameter sweep: the params tried and the metrics they produced. */
    public record OptimizeCellDto(Map<String, Double> params, Map<String, Double> metrics, double score) {}

    /**
     * Grid-search request. Sweeps {@code param1} from/to/step (and optionally {@code param2}),
     * scoring each combination by {@code metric} (e.g. "sharpe", "calmar", "totalReturnPct").
     * Reuses the backtest window / cost / timeframe knobs.
     */
    public record OptimizeRequest(List<String> symbols, LocalDate start, LocalDate end,
                                  Double capital, Double commissionBps, Double slippageBps,
                                  Boolean allowShort, String timeframe,
                                  String param1, Double from1, Double to1, Double step1,
                                  String param2, Double from2, Double to2, Double step2,
                                  String metric) {}

    public record OptimizeResultDto(String strategy, String metric,
                                    Map<String, Double> defaultParams, Map<String, Double> bestParams,
                                    OptimizeCellDto best, List<OptimizeCellDto> grid) {}

    /**
     * start/end are calendar dates (the backtest window the user picked), independent of bar interval.
     * {@code timeframe} resamples the stored bars UP to a coarser frame (NATIVE/H1/H4/D1/W1/MN);
     * the rest are optional risk/execution knobs — null means "engine default".
     */
    public record BacktestRequest(String strategyName, List<String> symbols,
                                  LocalDate start, LocalDate end, Double capital,
                                  Double commissionBps, Double slippageBps, Boolean allowShort,
                                  String timeframe, Integer execLag, Double positionSize,
                                  Double riskFreePct, Integer warmupBars,
                                  Double stopLossPct, Double takeProfitPct,
                                  Boolean perStrategyTimeframe) {}

    public record PairsRequest(String symbolA, String symbolB, Integer window, Double entry,
                               Double exit, LocalDate start, LocalDate end, Double capital,
                               Double commissionBps, Double slippageBps, String timeframe,
                               Double positionSize, Double stopLossPct, Double takeProfitPct) {}

    /**
     * Blend several strategies into one daily-rebalanced portfolio.
     * {@code strategyNames} null/empty => all enabled. {@code weighting}: "config" (each
     * strategy's stored weight), "equal", or "sharpe" (weight ∝ max(0, standalone Sharpe)).
     */
    public record EnsembleRequest(List<String> strategyNames, List<String> symbols,
                                  LocalDate start, LocalDate end, Double capital,
                                  Double commissionBps, Double slippageBps, Boolean allowShort,
                                  String timeframe, Double positionSize, String weighting,
                                  Boolean perStrategyTimeframe) {}

    public record EnsembleLegDto(String strategy, double weight, Map<String, Double> metrics) {}

    public record EnsembleResultDto(List<String> symbols, Instant start, Instant end, String timeframe,
                                    int bars, String weighting, Map<String, Double> metrics,
                                    List<String> dates, double[] equity, double[] benchmark,
                                    double[] drawdown, List<EnsembleLegDto> legs) {}

    public record TradeDto(String symbol, String side, Instant entryDate, Instant exitDate,
                           double entryPx, double exitPx, int bars, double returnPct) {}

    public record BacktestResultDto(Long runId, String strategy, List<String> symbols,
                                    Instant start, Instant end, String timeframe, int bars,
                                    Map<String, Double> metrics,
                                    List<String> dates, double[] equity, double[] benchmark,
                                    double[] drawdown, List<TradeDto> trades) {}

    /** One selectable resample target for the backtest form. */
    public record TimeframeDto(String id, String label, boolean nativeFrame) {}

    /**
     * Cache coverage for one symbol in the local {@code price_bar} store.
     * {@code fresh} = the newest cached bar is within the configured freshness
     * window ({@code quantplat.data.fresh-days}).
     */
    public record SymbolCoverageDto(String symbol, Instant firstBar, Instant lastBar,
                                    long bars, boolean fresh, String timeframe) {}

    public record LeaderboardEntryDto(Long runId, String strategy, String timeframe, Integer bars,
                                      Map<String, Double> metrics) {}

    /** Result of pruning run history to the top-N strategies. */
    public record PruneResultDto(String rankedBy, int keep, List<String> kept, List<String> disabled,
                                 int deletedRuns) {}

    public record SignalDto(String strategy, String category, String symbol, String signal,
                            boolean isNew, int bars, double weight, double close, Instant date,
                            String timeframe) {}

    public record EnabledUpdate(boolean enabled) {}
}
