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

@FeignClient(name = "execution-engine", url = "${application.execution-engine.url:}",
    configuration = ExecutionEngineClient.FeignConfiguration.class)
public interface ExecutionEngineClient {

  @PostMapping(value = "/api/v1/trades/command", consumes = MediaType.APPLICATION_JSON_VALUE)
  String command(@RequestBody TradeCommand command);

  class FeignConfiguration {
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
