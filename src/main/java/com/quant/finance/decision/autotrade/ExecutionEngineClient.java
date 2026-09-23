package com.quant.finance.decision.autotrade;

import feign.Logger;
import feign.codec.ErrorDecoder;
import feign.error.AnnotationErrorDecoder;
import java.net.URI;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * ExecutionEngine's TradeController. The url is passed per call ({@link CommandSender} always supplies it), so the declared url below is
 * a fixed placeholder, not a property lookup: {@code decision.execution-engine.url} resolving blank (the normal "not configured" default,
 * from {@code url: ${EXECUTION_ENGINE_URL:}}) is a property that is <em>present</em> with an empty value, not an absent one — Spring's
 * {@code ${x:default}} only falls back to {@code default} when {@code x} is absent, so pointing this annotation's url at that same
 * property would still resolve to blank, and a blank {@code @FeignClient} url makes Spring Cloud OpenFeign try service discovery and fail
 * to build the bean. The placeholder host below is never actually contacted, since {@link CommandSender#isConfigured()} gates every call.
 */
@FeignClient(name = "execution-engine", url = ExecutionEngineClient.UNCONFIGURED_URL,
    configuration = ExecutionEngineClient.FeignConfiguration.class)
public interface ExecutionEngineClient {

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
    public ErrorDecoder feignErrorDecoder() {
      return AnnotationErrorDecoder
          .builderFor(ExecutionEngineClient.class)
          .build();
    }
  }
}
