package com.quant.finance.decision.client;

import feign.Headers;
import feign.Logger;
import feign.codec.ErrorDecoder;
import feign.error.AnnotationErrorDecoder;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "execution-engine-client",
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
            return Logger.Level.FULL;
        }

        @Bean
        public ErrorDecoder feignErrorDecoder() {
            return AnnotationErrorDecoder
                .builderFor(ExecutionEngineClient.class)
                .build();
        }
    }
}
