package com.quant.finance.decision.client;

import com.quant.finance.decision.dto.Dtos.SignalDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forwards DecisionEngine's LONG/SHORT signals to your Java IB {@code ExecutionEngine}, via
 * either (or both) of its two intake surfaces:
 * <ul>
 *   <li>{@link #sendAlert} — the TradingView-webhook-shaped alert intake
 *       ({@code POST /api/v1/alerts/tv-hook}), which feeds ExecutionEngine's alert -> order
 *       pipeline (AlertScheduler -> StrategyService -> TradeService -> a live IB order).</li>
 *   <li>{@link #sendTradeCommand} — its typed trade-command surface
 *       ({@code POST /api/v1/trades/command}, the same one its Telegram bot uses), dispatched
 *       straight to {@code TradeService.buy()/sell()} with no {@code AlertEntity}/
 *       {@code StrategyEntity} name-matching step in between. As of this writing those
 *       buy()/sell() methods are empty stubs on the ExecutionEngine side (see its
 *       StockTradeExecutor/CryptoTradeExecutor) — sending a command there is a no-op until
 *       that's implemented.</li>
 * </ul>
 * FLAT signals are never forwarded on either path: neither surface models a close — the
 * alert webhook only models entries, and CLOSE_ALL on the command surface closes every
 * position, not just this symbol's.
 */
@Component
public class ExecutionEngineClient {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngineClient.class);

    private final RestClient restClient;
    private final boolean configured;

    @Value("${quantplat.execution-engine.exchange:SMART}")
    private String exchange;
    @Value("${quantplat.execution-engine.quote-currency:USD}")
    private String quoteCurrency;
    @Value("${quantplat.execution-engine.asset-class:STK}")
    private String assetClass;

    @Value("${quantplat.execution-engine.trade-command.order-type:MKT}")
    private String tcOrderType;
    @Value("${quantplat.execution-engine.trade-command.tif:DAY}")
    private String tcTif;
    /** Blank = omit quantity from the payload (let ExecutionEngine size the order once it can). */
    @Value("${quantplat.execution-engine.trade-command.quantity:}")
    private String tcQuantity;

    public ExecutionEngineClient(@Value("${quantplat.execution-engine.base-url:}") String baseUrl) {
        this.configured = baseUrl != null && !baseUrl.isBlank();
        this.restClient = configured ? RestClient.builder().baseUrl(baseUrl).build() : null;
    }

    public boolean isConfigured() {
        return configured;
    }

    /** Forwards a LONG/SHORT signal as a TradingView-shaped alert. Throws on a FLAT signal or if unconfigured. */
    public void sendAlert(SignalDto signal) {
        if (!configured) {
            throw new IllegalStateException(
                "ExecutionEngine integration not configured: set quantplat.execution-engine.base-url");
        }
        String action = switch (signal.signal()) {
            case "LONG" -> "buy";
            case "SHORT" -> "sell";
            default -> throw new IllegalArgumentException(
                "Only LONG/SHORT signals can be forwarded to ExecutionEngine, got: " + signal.signal());
        };

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("ticker", signal.symbol());
        payload.put(action, "1");
        payload.put("strategy", signal.strategy());
        payload.put("assetClass", assetClass);
        payload.put("exchange", exchange);
        payload.put("interval", "D");
        String closeStr = String.valueOf(signal.close());
        payload.put("close", closeStr);
        payload.put("open", closeStr);
        payload.put("high", closeStr);
        payload.put("low", closeStr);
        payload.put("quote", quoteCurrency);
        payload.put("time", DateTimeFormatter.ISO_INSTANT.format(signal.date()));
        payload.put("timenow", Instant.now().toString());

        restClient.post()
                .uri("/api/v1/alerts/tv-hook")
                .body(payload)
                .retrieve()
                .toBodilessEntity();

        log.info("Forwarded {} {} ({}) to ExecutionEngine", action.toUpperCase(), signal.symbol(), signal.strategy());
    }

    /**
     * Sends a LONG/SHORT signal as a typed {@code TradeCommandDto} (BUY/SELL) to
     * ExecutionEngine's {@code POST /api/v1/trades/command} — the same endpoint its Telegram
     * bot's {@code /buy} and {@code /sell} commands hit. Throws on a FLAT signal or if
     * unconfigured. Quantity/order-type/TIF come from
     * {@code quantplat.execution-engine.trade-command.*} — ExecutionEngine has no per-strategy
     * sizing lookup on this path (unlike the alert webhook, which sizes from its own
     * {@code StrategyEntity}), so quantity is left out of the payload entirely unless
     * explicitly configured.
     */
    public void sendTradeCommand(SignalDto signal) {
        if (!configured) {
            throw new IllegalStateException(
                "ExecutionEngine integration not configured: set quantplat.execution-engine.base-url");
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
        payload.put("orderType", tcOrderType);
        payload.put("tif", tcTif);
        if (tcQuantity != null && !tcQuantity.isBlank()) {
            try {
                payload.put("quantity", Double.valueOf(tcQuantity));
            } catch (NumberFormatException e) {
                log.warn("Ignoring invalid quantplat.execution-engine.trade-command.quantity={}", tcQuantity);
            }
        }
        if ("LMT".equalsIgnoreCase(tcOrderType)) {
            payload.put("limitPrice", signal.close());
        }
        payload.put("rawText", String.format("DecisionEngine %s %s @ %s tf=%s close=%s",
                signal.strategy(), signal.symbol(), signal.date(), signal.timeframe(), signal.close()));

        String ack = restClient.post()
                .uri("/api/v1/trades/command")
                .body(payload)
                .retrieve()
                .body(String.class);

        log.info("Sent {} command for {} ({}) to ExecutionEngine/trades — {}",
                command, signal.symbol(), signal.strategy(), ack);
    }
}
