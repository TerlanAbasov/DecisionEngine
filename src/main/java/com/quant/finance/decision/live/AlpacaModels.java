package com.quant.finance.decision.live;

import java.time.Instant;

/** Plain values for what Alpaca's trading API returns (only the fields the job uses). */
public final class AlpacaModels {
    private AlpacaModels() {}

    public record AccountInfo(String status, double equity, double cash, double buyingPower,
                              double longMarketValue, double shortMarketValue,
                              boolean tradingBlocked, boolean accountBlocked, boolean shortingEnabled) {}

    public record MarketClock(boolean open, Instant timestamp, Instant nextOpen, Instant nextClose) {}

    /** {@code qty} is signed: negative for a short position. */
    public record PositionInfo(String symbol, double qty, double avgEntryPrice, double currentPrice,
                               double marketValue, double unrealizedPl) {}

    public record AssetInfo(String symbol, boolean tradable, boolean shortable, boolean easyToBorrow,
                            boolean fractionable, String status) {}

    /** {@code status} is Alpaca's lower-case order status (new, accepted, partially_filled, filled, canceled, rejected, …). */
    public record OrderInfo(String id, String clientOrderId, String symbol, String side, double qty,
                            String status, double filledQty, Double filledAvgPrice,
                            Instant submittedAt, Instant filledAt) {
        public boolean isFilled() { return "filled".equals(status); }

        /** No further change will happen to this order. */
        public boolean isTerminal() {
            return switch (status == null ? "" : status) {
                case "filled", "canceled", "expired", "rejected", "done_for_day", "replaced", "suspended", "stopped" -> true;
                default -> false;
            };
        }
    }
}
