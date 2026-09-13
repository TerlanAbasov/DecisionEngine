package com.quant.finance.decision.client;

import feign.Headers;
import feign.RequestLine;

import java.util.Map;

/**
 * Plain-Feign contract for ExecutionEngine's {@code TradeController}
 * ({@code com.quant.finance.execution.controller.TradeController}). Payload is sent as a
 * generic {@code Map} rather than sharing ExecutionEngine's {@code TradeCommandDto} Java type
 * across repos — {@link ExecutionEngineClient} builds the field names to match what that DTO
 * deserializes.
 */
interface ExecutionEngineApi {

    /** POST /api/v1/trades/command — typed trade command; returns a short ack string. */
    @RequestLine("POST /api/v1/trades/command")
    @Headers("Content-Type: application/json")
    String tradeCommand(Map<String, Object> body);
}
