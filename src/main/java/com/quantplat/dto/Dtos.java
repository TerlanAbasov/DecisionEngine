package com.quantplat.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Request/response payloads for the REST API. */
public final class Dtos {
    private Dtos() {}

    public record StrategyDto(String name, String category, String direction,
                              String description, boolean enabled, Map<String, Double> params) {}

    public record BacktestRequest(String strategyName, List<String> symbols,
                                  LocalDate start, LocalDate end, Double capital,
                                  Double commissionBps, Double slippageBps, Boolean allowShort) {}

    public record PairsRequest(String symbolA, String symbolB, Integer window, Double entry,
                               Double exit, LocalDate start, LocalDate end, Double capital,
                               Double commissionBps, Double slippageBps) {}

    public record TradeDto(String symbol, String side, LocalDate entryDate, LocalDate exitDate,
                           double entryPx, double exitPx, int bars, double returnPct) {}

    public record BacktestResultDto(Long runId, String strategy, List<String> symbols,
                                    LocalDate start, LocalDate end, Map<String, Double> metrics,
                                    List<String> dates, double[] equity, double[] benchmark,
                                    double[] drawdown, List<TradeDto> trades) {}

    public record LeaderboardEntryDto(Long runId, String strategy, Map<String, Double> metrics) {}

    public record SignalDto(String strategy, String category, String symbol, String signal,
                            boolean isNew, int bars, double weight, double close, LocalDate date) {}

    public record EnabledUpdate(boolean enabled) {}
}
