package com.quant.finance.decision.service;

import com.quant.finance.decision.dto.Dtos.SignalDto;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends DecisionEngine's LONG/SHORT signals to ExecutionEngine's
 * {@code com.quant.finance.execution.controller.TradeController}
 * ({@code POST /api/v1/trades/command}) — the same typed trade-command surface its Telegram
 * bot's {@code /buy}/{@code /sell} commands use. This is the only ExecutionEngine endpoint
 * this integration talks to; its {@code AlertController} (TradingView-webhook-shaped alert
 * intake) is intentionally not used.
 * <p>
 * A command is dispatched straight to {@code TradeService.buy()/sell()} with no
 * {@code AlertEntity}/{@code StrategyEntity} name-matching step in between. As of this writing
 * those {@code buy()}/{@code sell()} methods are empty stubs on the ExecutionEngine side (see
 * its {@code StockTradeExecutor}/{@code CryptoTradeExecutor}) — sending a command there is a
 * no-op until that's implemented.
 * <p>
 * FLAT signals are never sent: {@code TradeCommandDto} has no close/exit command that targets
 * a single symbol (its {@code CLOSE_ALL} closes every position).
 * <p>
 * The actual HTTP call is {@link com.quant.finance.decision.client.ExecutionEngineClient} (in the {@code client} package —
 * a {@code @FeignClient} built the same way as ExecutionEngine's own {@code RoutingClient}).
 * This class is the service-layer wrapper around it: payload assembly, config, and the
 * "is the integration even configured" guard, since {@code @FeignClient} has no built-in
 * notion of an optional/absent target URL.
 */
@Component
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    private final com.quant.finance.decision.client.ExecutionEngineClient api;
    @Getter
    private final boolean configured;

    @Value("${decision.execution-engine.order-type:MKT}")
    private String orderType;
    @Value("${decision.execution-engine.tif:DAY}")
    private String tif;
    /** Blank = omit quantity from the payload (ExecutionEngine has no sizing on this path yet). */
    @Value("${decision.execution-engine.quantity:}")
    private String quantity;

    public CommandService(com.quant.finance.decision.client.ExecutionEngineClient api,
                          @Value("${decision.execution-engine.base-url:}") String baseUrl) {
        this.api = api;
        this.configured = baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * Sends a LONG/SHORT signal as a {@code TradeCommandDto} (BUY/SELL) to
     * {@code POST /api/v1/trades/command}. Throws on a FLAT signal or if unconfigured.
     * Quantity/order-type/TIF come from {@code decision.execution-engine.*} — ExecutionEngine
     * has no per-strategy sizing lookup on this path, so quantity is left out of the payload
     * entirely unless explicitly configured.
     */
    public void sendTradeCommand(SignalDto signal) {
        if (!configured) {
            throw new IllegalStateException(
                "ExecutionEngine integration not configured: set decision.execution-engine.base-url");
        }
        String command = switch (signal.signal()) {
            case "LONG" -> "BUY";
            case "SHORT" -> "SELL";
            default -> throw new IllegalArgumentException(
                "Only LONG/SHORT signals can be sent as a trade command, got: " + signal.signal());
        };

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("command", command);
        payload.put("action", command);
        payload.put("identifier", signal.symbol());
        payload.put("strategy", signal.strategy());
        payload.put("orderType", orderType);
        payload.put("tif", tif);
        if (quantity != null && !quantity.isBlank()) {
            try {
                payload.put("quantity", Double.valueOf(quantity));
            } catch (NumberFormatException e) {
                log.warn("Ignoring invalid decision.execution-engine.quantity={}", quantity);
            }
        }
        if ("LMT".equalsIgnoreCase(orderType)) {
            payload.put("limitPrice", signal.close());
        }
        payload.put("rawText", String.format("DecisionEngine %s %s @ %s tf=%s close=%s",
                signal.strategy(), signal.symbol(), signal.date(), signal.timeframe(), signal.close()));

        String ack = api.tradeCommand(payload);

        log.info("Sent {} command for {} ({}) to ExecutionEngine/trades — {}",
                command, signal.symbol(), signal.strategy(), ack);
    }
}
