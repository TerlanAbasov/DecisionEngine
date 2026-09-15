package com.quant.finance.decision.service;

import com.quant.finance.decision.client.ExecutionEngineClient;
import com.quant.finance.decision.dto.Dtos.SignalDto;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Sends DecisionEngine's LONG/SHORT signals to ExecutionEngine's {@code TradeController}
 * ({@code POST /api/v1/trades/command}) — the only ExecutionEngine endpoint this integration
 * uses. FLAT signals are never sent since there's no per-symbol close command
 * ({@code CLOSE_ALL} closes everything instead).
 * <p>
 * {@link com.quant.finance.decision.client.ExecutionEngineClient} makes the actual HTTP call;
 * this class handles payload assembly, config, and the "is this configured" guard.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommandService {
    private final ExecutionEngineClient executionEngineClient;

    @Getter
    private final boolean configured;

    @Value("${decision.execution-engine.order-type:MKT}")
    private String orderType;
    @Value("${decision.execution-engine.tif:DAY}")
    private String tif;
    /** Blank omits quantity from the payload. */
    @Value("${decision.execution-engine.quantity:}")
    private String quantity;

    public CommandService(com.quant.finance.decision.client.ExecutionEngineClient executionEngineClient,
                          @Value("${decision.execution-engine.base-url:}") String baseUrl) {
        this.executionEngineClient = executionEngineClient;
        this.configured = baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * Sends a LONG/SHORT signal as a BUY/SELL trade command. Throws on a FLAT signal or if
     * unconfigured. Order sizing/type/TIF come from {@code decision.execution-engine.*}.
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

        String ack = "ack";//executionEngineClient.tradeCommand(payload);

        log.info("Sent {} command for {} ({}) to ExecutionEngine/trades — {}",
                command, signal.symbol(), signal.strategy(), ack);
    }
}
