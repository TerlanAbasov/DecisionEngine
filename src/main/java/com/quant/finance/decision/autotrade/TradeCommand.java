package com.quant.finance.decision.autotrade;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.quant.finance.decision.autotrade.AutoTradeSettings.OrderType;
import com.quant.finance.decision.autotrade.AutoTradeSettings.TimeInForce;
import com.quant.finance.decision.dto.Dtos.SignalDto;

import java.time.Instant;

/**
 * The body of ExecutionEngine's {@code POST /api/v1/trades/command} for a BUY or SELL: {@code command} and {@code action} are both the side,
 * {@code identifier} the symbol. Fields that do not apply are left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TradeCommand(Side command, String identifier, String strategy, Side action, double quantity,
                           OrderType orderType, Double limitPrice, TimeInForce tif, String rawText) {

    public enum Side { BUY, SELL }

    /** The command for a signal the job just detected. */
    static TradeCommand of(Flip flip, AutoTradeSettings settings) {
        return build(flip.side(), flip.symbol(), flip.strategy(), flip.timeframe().name(), flip.barOpen(), flip.close(), settings, "auto");
    }

    /** The command for a signal the user chose to forward by hand; only LONG and SHORT can be traded. */
    static TradeCommand of(SignalDto signal, AutoTradeSettings settings) {
        Side side = switch (signal.signal()) {
            case "LONG" -> Side.BUY;
            case "SHORT" -> Side.SELL;
            default -> throw new IllegalArgumentException("Only LONG/SHORT signals can be sent as a trade command, got: " + signal.signal());
        };
        return build(side, signal.symbol(), signal.strategy(), signal.timeframe(), signal.date(), signal.close(), settings, "manual");
    }

    private static TradeCommand build(Side side, String symbol, String strategy, String timeframe, Instant barOpen, double close,
                                      AutoTradeSettings settings, String source) {
        Double limitPrice = settings.orderType() == OrderType.LMT ? close : null;
        String note = String.format("DecisionEngine %s %s %s tf=%s bar=%s close=%s", source, strategy, symbol, timeframe, barOpen, close);
        return new TradeCommand(side, symbol, strategy, side, settings.quantity(), settings.orderType(), limitPrice, settings.tif(), note);
    }
}
