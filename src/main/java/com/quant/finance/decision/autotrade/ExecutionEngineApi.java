package com.quant.finance.decision.autotrade;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * ExecutionEngine's TradeController: it answers with a short text and reports its own failures in that text with HTTP 200, so {@link CommandSender} judges the reply, not just the status.
 * The base url is passed per call (a Feign client cannot start from a blank property); the placeholder host is never contacted, since {@link CommandSender#isConfigured()} gates every call.
 */
@FeignClient(name = "execution-engine", url = ExecutionEngineApi.UNCONFIGURED_URL, configuration = ExecutionEngineApi.FeignConfiguration.class)
public interface ExecutionEngineApi {

    String UNCONFIGURED_URL = "http://execution-engine.invalid";

    @PostMapping(value = "/api/v1/trades/command", consumes = MediaType.APPLICATION_JSON_VALUE)
    String command(URI baseUrl, @RequestBody TradeCommand command);

    class FeignConfiguration {
        /** BASIC (method, url, status, time): the payload is in the command log already. */
        @Bean
        Logger.Level feignLoggerLevel() {
            return Logger.Level.BASIC;
        }

        @Bean
        Request.Options feignOptions() {
            return new Request.Options(3, TimeUnit.SECONDS, 15, TimeUnit.SECONDS, false);
        }

        /** Never retry inside Feign: a retried order could be sent twice. */
        @Bean
        Retryer feignRetryer() {
            return Retryer.NEVER_RETRY;
        }
    }
}
