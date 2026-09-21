package com.quant.finance.decision.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Request/response payloads for the REST API. */
public final class Dtos {
    private Dtos() {}

    /**
     * {@code direction} is the effective stance (after any override), {@code nativeDirection} the strategy's own; {@code params} are the effective params
     * (overrides on defaults), {@code defaultParams} the catalog defaults, {@code overridden} the keys the user changed.
     */
    public record StrategyDto(String name, String category, String direction, String nativeDirection,
                              String description, boolean enabled, boolean archived, double weight, boolean invert,
                              String directionOverride, List<String> tags, boolean favorite, String notes,
                              String recommendedTimeframe, boolean intraday,
                              Double defaultStopLossPct, Double defaultTakeProfitPct,
                              Map<String, Double> params, Map<String, Double> defaultParams,
                              List<String> overridden) {}

    /** Partial update of a strategy's behaviour controls — any null field is left unchanged. */
    public record StrategyControlsUpdate(Double weight, Boolean invert, String directionOverride,
                                         List<String> tags, Boolean favorite, String notes,
                                         String recommendedTimeframe, Boolean intraday, Boolean archived,
                                         Double defaultStopLossPct, Double defaultTakeProfitPct) {}

    /** One cell of a parameter sweep: the params tried and the metrics they produced. */
    public record OptimizeCellDto(Map<String, Double> params, Map<String, Double> metrics, double score) {}

    /**
     * Grid-search request: sweeps {@code param1} from/to/step (and optionally {@code param2}), scoring each combination by {@code metric}
     * (e.g. "sharpe", "calmar", "totalReturnPct"), reusing the backtest window / cost / timeframe knobs.
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

    /** Request for a risk-default sweep (timeframe x stop-loss% x take-profit%). */
    public record RiskOptimizeRequest(List<String> symbols, LocalDate start, LocalDate end,
                                      Double capital, Double commissionBps, Double slippageBps,
                                      Boolean allowShort) {}

    /**
     * One cell of a risk-default sweep: a (timeframe, stopLossPct, takeProfitPct) combo and
     * the portfolio metrics it produced.
     */
    public record RiskCellDto(String timeframe, double stopLossPct, double takeProfitPct,
                              Map<String, Double> metrics, double score) {}

    /**
     * Best (timeframe, stopLossPct, takeProfitPct) combo, selected on the training slice only ({@code trainFraction}, e.g. 0.7); {@code outOfSample} re-runs it
     * on the held-out slice — the honest number, since {@code best}'s score is in-sample and will look better (that gap is overfitting, not a bug).
     */
    public record RiskOptimizeResultDto(String strategy, String metric, RiskCellDto best,
                                        RiskCellDto outOfSample, double trainFraction, int cellsEvaluated) {}

    /** One strategy's outcome from a bulk risk-default sweep, and whether it was saved. */
    public record RiskOptimizeBulkEntryDto(String strategy, RiskCellDto best, RiskCellDto outOfSample,
                                           boolean saved, String error) {}

    public record RiskOptimizeBulkResultDto(int strategies, int succeeded, int failed,
                                            List<RiskOptimizeBulkEntryDto> results) {}

    /**
     * start/end are the calendar dates of the backtest window, independent of bar interval; {@code timeframe} resamples the stored bars UP to a coarser frame.
     * The remaining risk/execution knobs are optional: null means "engine default".
     */
    public record BacktestRequest(String strategyName, List<String> symbols,
                                  LocalDate start, LocalDate end, Double capital,
                                  Double commissionBps, Double slippageBps, Boolean allowShort,
                                  String timeframe, Integer execLag, Double positionSize,
                                  Double riskFreePct, Integer warmupBars,
                                  Double stopLossPct, Double takeProfitPct,
                                  Boolean perStrategyTimeframe,
                                  /** run-all: include disabled (but not archived) strategies too */
                                  Boolean includeDisabled,
                                  /** run-all: restrict the leaderboard to these strategy names (blank = all) */
                                  List<String> strategyNames) {}

    public record PairsRequest(String symbolA, String symbolB, Integer window, Double entry,
                               Double exit, LocalDate start, LocalDate end, Double capital,
                               Double commissionBps, Double slippageBps, String timeframe,
                               Double positionSize, Double stopLossPct, Double takeProfitPct) {}

    /**
     * Blends several strategies into one daily-rebalanced portfolio; {@code strategyNames} null/empty means all enabled.
     * {@code weighting} is "config" (stored weights), "equal", or "sharpe" (weight ∝ max(0, standalone Sharpe)).
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

    /**
     * One symbol's standalone result within a run: the full metric set from its own return
     * stream, and its return per calendar year (UTC, percent, oldest first).
     */
    public record SymbolResultDto(String symbol, Map<String, Double> metrics,
                                  Map<String, Double> yearlyReturnsPct) {}

    /**
     * The run: headline metrics, curves and the per-symbol breakdown; {@code symbolResults} is empty for runs saved before it existed.
     * The trade log is not embedded (a run can have tens of thousands) — {@code tradeCount} counts it and {@code GET /api/backtests/{id}/trades} pages it.
     */
    public record BacktestResultDto(Long runId, String strategy, List<String> symbols,
                                    Instant start, Instant end, String timeframe, int bars,
                                    Map<String, Double> metrics,
                                    List<String> dates, double[] equity, double[] benchmark,
                                    double[] drawdown,
                                    Map<String, Double> symbolReturnsPct,
                                    List<SymbolResultDto> symbolResults,
                                    Map<String, Double> yearlyReturnsPct,
                                    long tradeCount) {}

    /**
     * One trade with its accounting spelled out, standalone per symbol: percentages of starting capital, {@code notional} = capital × exposure.
     * gross − commission − slippage = net; {@code contribPct} is its share of the portfolio return; {@code open} = still held, marked to market.
     */
    public record TradeDetailDto(long id, String symbol, String side, Instant entryDate, Instant exitDate,
                                 double entryPx, double exitPx, int bars, boolean open,
                                 double exposure, double notional, double shares,
                                 double grossPct, double commissionPct, double slippagePct,
                                 double netPct, double contribPct,
                                 double grossPnl, double commission, double slippage, double netPnl) {}

    /** Totals over every trade matching the filter (not just the current page). */
    public record TradeSummaryDto(long trades, long longs, long shorts, long wins, double winRatePct,
                                  double grossPnl, double commission, double slippage, double netPnl,
                                  double avgNetPct, double bestNetPct, double worstNetPct) {}

    public record TradePageDto(List<TradeDetailDto> items, long total, int page, int size,
                               TradeSummaryDto summary) {}

    /** One selectable resample target for the backtest form. */
    public record TimeframeDto(String id, String label, boolean nativeFrame) {}

    /**
     * Cache coverage for one symbol in the local {@code price_bar} store; {@code fresh} = the newest cached bar is within
     * the freshness window ({@code decision.data.fresh-days}).
     */
    public record SymbolCoverageDto(String symbol, Instant firstBar, Instant lastBar,
                                    long bars, boolean fresh, String timeframe) {}

    public record LeaderboardEntryDto(Long runId, String strategy, String timeframe, Integer bars,
                                      List<String> symbols, Map<String, Double> metrics) {}

    /** Result of pruning the strategy set to the most profitable subset. */
    public record PruneResultDto(String rankedBy, int rankWindow, int ranked, int keep,
                                 List<String> kept, List<String> losers, int deletedRuns) {}

    public record SignalDto(String strategy, String category, String symbol, String signal,
                            boolean isNew, int bars, double weight, double close, Instant date,
                            String timeframe) {}

    /** One OHLCV bar for the price-chart view. {@code date} is the bar's open time (ISO-8601). */
    public record BarDto(String date, double open, double high, double low, double close, double volume) {}

    public record PriceSeriesDto(String symbol, String timeframe, int bars,
                                 Instant start, Instant end, List<BarDto> data) {}

    /** A position change for a strategy on the chart. {@code type}: BUY | SELL | EXIT. */
    public record SignalMarkerDto(String date, double price, String type, double position) {}

    public record StrategySignalsDto(String strategy, String timeframe, List<SignalMarkerDto> markers) {}

    public record SignalOverlayDto(String symbol, List<StrategySignalsDto> strategies) {}

    public record EnabledUpdate(boolean enabled) {}

    // ---- background backtest jobs -------------------------------------------------------------

    /** One stage of a job (prepare / load / run / save) with its own counters; stages can overlap. */
    public record JobStepDto(String key, String label, String state, int done, int total, String detail) {}

    /** A symbol or strategy taking part in a job. state: pending | running | done | failed | nodata. */
    public record JobItemDto(String name, String state, int done, int total) {}

    /**
     * A backtest running (or finished) in the background; {@code percent} is the weighted progress over all steps.
     * {@code result} appears once COMPLETED: a BacktestResultDto (RUN / PAIRS), a list of LeaderboardEntryDto (RUN_ALL) or an EnsembleResultDto (ENSEMBLE).
     */
    public record JobDto(String id, String kind, String status, String title,
                         Instant createdAt, Instant startedAt, Instant finishedAt, long elapsedMs,
                         int percent, List<JobStepDto> steps, List<JobItemDto> symbols,
                         List<JobItemDto> strategies, List<String> running,
                         boolean cancelRequested, String error, Object result) {}
}
