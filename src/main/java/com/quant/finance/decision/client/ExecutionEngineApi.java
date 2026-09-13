package com.quant.finance.decision.client;

import feign.Headers;
import feign.RequestLine;

import java.util.Map;

/**
 * Plain-Feign contract for ExecutionEngine's two signal intake surfaces. Payloads are sent as
 * a generic {@code Map} (rather than sharing ExecutionEngine's {@code TVAlertDto}/
 * {@code TradeCommandDto} Java types across repos) — {@link ExecutionEngineClient} builds the
 * field names to match what those DTOs deserialize.
 */
interface ExecutionEngineApi {

    /** POST /api/v1/alerts/tv-hook — TradingView-webhook-shaped alert intake. Fire-and-forget. */
    @RequestLine("POST /api/v1/alerts/tv-hook")
    @Headers("Content-Type: application/json")
    void tvHook(Map<String, ?> body);

    /** POST /api/v1/trades/command — typed trade command; returns a short ack string. */
    @RequestLine("POST /api/v1/trades/command")
    @Headers("Content-Type: application/json")
    String tradeCommand(Map<String, Object> body);
}
