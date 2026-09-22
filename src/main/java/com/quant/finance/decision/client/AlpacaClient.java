package com.quant.finance.decision.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.quant.finance.decision.error.AlpacaApiException;
import feign.Logger;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;

/**
 * Alpaca's market-data API (bars and latest prices): the base URL is passed per call, so a symbol's data and a strategy's decisions always
 * come from Alpaca's real data host. Returns JSON (Alpaca sends numbers as strings); errors are {@link AlpacaApiException}.
 */
@FeignClient(name = "alpaca-data-client",
    url = "${decision.alpaca.data-base-url:https://data.alpaca.markets}",
    configuration = AlpacaClient.FeignConfiguration.class)
public interface AlpacaClient {

    /** One page of bars; pass the previous page's {@code next_page_token} as {@code pageToken} (null for the first). */
    @GetMapping("/v2/stocks/{symbol}/bars")
    JsonNode bars(URI dataBaseUrl,
                  @PathVariable("symbol") String symbol,
                  @RequestParam("timeframe") String timeframe,
                  @RequestParam("start") String start,
                  @RequestParam("end") String end,
                  @RequestParam("limit") int limit,
                  @RequestParam("adjustment") String adjustment,
                  @RequestParam("feed") String feed,
                  @RequestParam(value = "page_token", required = false) String pageToken);

    /** Latest trade, quote and bars for a comma-separated list of symbols. */
    @GetMapping("/v2/stocks/snapshots")
    JsonNode snapshots(URI dataBaseUrl, @RequestParam("symbols") String symbols, @RequestParam("feed") String feed);

    class FeignConfiguration {
        /** BASIC (method, url, status, time): FULL would log the secret-key header too. */
        @Bean
        Logger.Level feignLoggerLevel() {
            return Logger.Level.BASIC;
        }

        @Bean
        RequestInterceptor alpacaAuthInterceptor(AlpacaCredentials creds) {
            return AlpacaFeign.authInterceptor(creds);
        }

        @Bean
        RequestInterceptor alpacaHostGuardInterceptor() {
            return AlpacaFeign.hostGuardInterceptor();
        }

        @Bean
        ErrorDecoder feignErrorDecoder() {
            return AlpacaFeign.errorDecoder();
        }

        @Bean
        Request.Options feignOptions() {
            return AlpacaFeign.options();
        }

        /** Never retry inside Feign, to keep one call one request; {@link AlpacaCalls#read} is what retries a read. */
        @Bean
        Retryer feignRetryer() {
            return Retryer.NEVER_RETRY;
        }
    }
}
