package com.quant.finance.decision.client;

import feign.Headers;
import feign.Logger;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * Same pattern as ExecutionEngine's own
 * {@code com.quant.finance.execution.client.RoutingClient}: a {@code @FeignClient} interface
 * with Spring MVC method annotations, registered via {@code @EnableFeignClients} on the
 * application class (see {@code DecisionEngineApplication}).
 * <p>
 * Payload is a generic {@code Map} rather than sharing ExecutionEngine's
 * {@code TradeCommandDto} Java type across repos — {@link ExecutionEngineClient} builds the
 * field names to match what that DTO deserializes.
 */
@FeignClient(name = "execution-engine-client",
    // Deliberately NOT quantplat.execution-engine.base-url: that one is blank by default
    // (the "integration disabled" signal ExecutionEngineClient.configured reads), and
    // @FeignClient treats an empty url as "resolve by service discovery instead", which
    // needs a load-balancer bean and crashes app startup outright without one. feign-url
    // resolves from the same EXECUTION_ENGINE_URL env var but always falls back to a
    // syntactically valid, unroutable host (RFC 2606 .invalid) instead of blank — only ever
    // dialed if the configured-guard is bypassed.
    url = "${quantplat.execution-engine.feign-url}",
    configuration = ExecutionEngineClient.FeignConfiguration.class)
public interface ExecutionEngineClient {

    /** POST /api/v1/trades/command — typed trade command; returns a short ack string. */
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
