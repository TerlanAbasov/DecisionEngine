package com.quant.finance.decision.autotrade;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.quant.finance.decision.autotrade.AutoTradeSettings.OrderType;
import com.quant.finance.decision.autotrade.AutoTradeSettings.TimeInForce;
import com.quant.finance.decision.dto.Dtos.SignalDto;

import java.math.BigDecimal;

/**
 * The body of ExecutionEngine's {@code POST /api/v1/trades/command}: every field of its {@code TradeCommandDto} except {@code chatId} and
 * {@code rawText}, which are Telegram-bot fields with nothing to carry from here. {@code command} and {@code action} are both the side;
 * {@code identifier} is the symbol; {@code open}/{@code high}/{@code low}/{@code close} are the signal bar's own prices, when known.
 * Fields that do not apply (no limit price on a market order, no bar prices for a hand-forwarded signal) are left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TradeCommand(Side command, String identifier, String strategy, Side action, double quantity,
                           OrderType orderType, Double limitPrice, TimeInForce tif,
                           BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {

    public enum Side { BUY, SELL }

    /** The command for a signal the job just detected: the signal bar's own OHLC travels with it. */
    static TradeCommand of(Flip flip, AutoTradeSettings settings) {
        return build(flip.side(), flip.symbol(), flip.strategy(), settings,
                price(flip.open()), price(flip.high()), price(flip.low()), price(flip.close()));
    }

    /** The command for a signal the user chose to forward by hand; only LONG and SHORT can be traded. Only the close is known here. */
    static TradeCommand of(SignalDto signal, AutoTradeSettings settings) {
        Side side = switch (signal.signal()) {
            case "LONG" -> Side.BUY;
            case "SHORT" -> Side.SELL;
            default -> throw new IllegalArgumentException("Only LONG/SHORT signals can be sent as a trade command, got: " + signal.signal());
        };
        return build(side, signal.symbol(), signal.strategy(), settings, null, null, null, price(signal.close()));
    }

    private static TradeCommand build(Side side, String symbol, String strategy, AutoTradeSettings settings,
                                      BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        Double limitPrice = settings.orderType() == OrderType.LMT && close != null ? close.doubleValue() : null;
        return new TradeCommand(side, symbol, strategy, side, settings.quantity(), settings.orderType(), limitPrice, settings.tif(),
                open, high, low, close);
    }

    private static BigDecimal price(double v) { return BigDecimal.valueOf(v); }
}
