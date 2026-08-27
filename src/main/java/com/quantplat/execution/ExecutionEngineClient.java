package com.quantplat.execution;

import com.quantplat.dto.Dtos.SignalDto;
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
 * Forwards DecisionEngine's LONG/SHORT signals to your Java IB {@code ExecutionEngine}'s
 * TradingView-webhook-shaped alert intake ({@code POST /api/v1/alerts/tv-hook}), which feeds
 * its existing alert -> order pipeline (AlertScheduler -> StrategyService -> TradeService ->
 * a live IB order). FLAT signals are never forwarded: that webhook only models entries
 * (buy/sell), not closes — ExecutionEngine's CLOSE_ALL command is a separate, manual surface.
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
}
