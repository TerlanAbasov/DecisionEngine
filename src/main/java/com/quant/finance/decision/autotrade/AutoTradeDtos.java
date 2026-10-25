package com.quant.finance.decision.autotrade;

import java.time.Instant;
import java.util.List;

/** Shapes the auto-trading API returns. */
public final class AutoTradeDtos {
    private AutoTradeDtos() {}

    public record PageDto<T>(List<T> items, long total, int page, int size) {}

    /** {@code configured}: ExecutionEngine's url is set; {@code running}: a pass is under way; {@code lastRun}: the last pass that had anything to look at. */
    public record StatusDto(AutoTradeSettings settings, boolean running, RunSummary lastRun, int checkEverySeconds) {}

    public record CommandDto(
            Long id,
            Instant createdAt,
            String strategy,
            String symbol,
            String timeframe,
            Instant barOpen,
            String side,
            double quantity,
            String orderType,
            Double limitPrice,
            String status,
            String response) {

        static CommandDto of(AutoTradeCommandEntity command) {
            return new CommandDto(
                    command.getId(),
                    command.getCreatedAt(),
                    command.getStrategy(),
                    command.getSymbol(),
                    command.getTimeframe(),
                    command.getBarOpen(),
                    command.getSide(),
                    command.getQuantity(),
                    command.getOrderType(),
                    command.getLimitPrice(),
                    command.getStatus(),
                    command.getResponse());
        }
    }
}
