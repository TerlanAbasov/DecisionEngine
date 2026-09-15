package com.quant.finance.decision.client;

import feign.Headers;
import feign.Logger;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * Feign client mirroring ExecutionEngine's own {@code RoutingClient} pattern. Uses a generic
 * {@code Map} payload (not ExecutionEngine's {@code TradeCommandDto}) since the DTO type
 * isn't shared across repos — field names must match what it deserializes.
 */
@FeignClient(name = "execution-engine-client",
    // Not decision.execution-engine.base-url (blank by default): @FeignClient can't take a
    // blank url without a load-balancer bean. feign-url falls back to an unroutable
    // RFC 2606 .invalid host instead, only ever dialed if the configured-guard is bypassed.
    url = "${decision.execution-engine.feign-url}",
    configuration = ExecutionEngineClient.FeignConfiguration.class)
public interface ExecutionEngineClient {

    /** Typed trade command; returns a short ack string. */
    @PostMapping("/api/v1/trades/command")
    @Headers("Content-Type: application/json")
    String tradeCommand(@RequestBody Map<String, Object> body);

    class FeignConfiguration {
        @Bean
        Logger.Level feignLoggerLevel() {
            return Logger.Level.BASIC;
        }
    }
}
