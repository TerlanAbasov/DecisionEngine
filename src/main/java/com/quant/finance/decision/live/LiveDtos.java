package com.quant.finance.decision.live;

import java.time.Instant;
import java.util.List;

/** Shapes the paper-trading API returns. */
public final class LiveDtos {
    private LiveDtos() {}

    public record PageDto<T>(List<T> items, long total, int page, int size) {}

    public record CycleDto(Long id, Instant startedAt, Instant finishedAt, String status, String mode, String triggeredBy,
                           boolean dryRun, String message, Integer symbols, Integer strategies, Integer signals,
                           Integer ordersPlanned, Integer ordersFilled, Integer ordersFailed, Integer tradesClosed,
                           Integer errors, Long durationMs) {}

    public record BrokerDto(boolean reachable, String error, String accountStatus, Double equity, Double cash,
                            Double buyingPower, Double longValue, Double shortValue, Boolean marketOpen,
                            Instant nextOpen, Instant nextClose) {}

    public record StatusDto(LiveSettings settings, boolean scheduled, boolean cycleRunning, Instant nextRun,
                            boolean paperEndpoint, boolean credentialsConfigured, BrokerDto broker, CycleDto lastCycle,
                            int openSlots, double realizedPnl, double unrealizedPnl) {}

    public record StrategyPerfDto(String strategy, boolean inScope, int openPositions, int longs, int shorts,
                                  double realizedPnl, double unrealizedPnl, double totalPnl, double returnPct,
                                  int trades, int wins, Double winRatePct, Instant lastSignalAt) {}

    public record SlotDto(String symbol, int direction, double qty, Double entryPrice, Instant entryTime, Double lastPrice,
                          double unrealizedPnl, double realizedPnl, Double lastSignal, Instant lastSignalAt, int blockedDir) {}

    public record TradeDto(Long id, String symbol, String side, double qty, Instant entryTime, double entryPrice,
                           Instant exitTime, double exitPrice, double pnlUsd, double returnPct, String exitReason) {}

    public record PnlPointDto(Instant ts, double realized, double unrealized, double total) {}

    public record StrategyDetailDto(StrategyPerfDto summary, List<SlotDto> slots, List<TradeDto> trades, List<PnlPointDto> curve) {}

    public record PositionDto(String symbol, double actualQty, double virtualQty, double gap, Double price,
                              Double marketValue, Double unrealizedPl, boolean managed) {}

    public record OrderDto(Long id, Long cycleId, String symbol, String side, double qty, String status, String alpacaOrderId,
                           Double filledQty, Double filledAvgPrice, Instant submittedAt, Instant filledAt, String error,
                           boolean dryRun, String reason, Double targetQty, Double positionBefore) {}

    public record EquityPointDto(Instant ts, double equity, Double cash, Double buyingPower) {}
}
