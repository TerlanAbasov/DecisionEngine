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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;
import java.util.Map;

/**
 * Alpaca's API: paper-account trading (the fixed url) and market data (another host, so those calls take a base {@link URI}).
 * Returns JSON (Alpaca sends numbers as strings); errors are {@link AlpacaApiException}; {@link AlpacaFeign#hostGuardInterceptor()} pins each call's host.
 */
@FeignClient(name = "alpaca-trading-client",
    url = "${decision.alpaca.trading-base-url:https://paper-api.alpaca.markets}",
    configuration = AlpacaClient.FeignConfiguration.class)
public interface AlpacaClient {

    @GetMapping("/v2/account")
    JsonNode account();

    @GetMapping("/v2/clock")
    JsonNode clock();

    @GetMapping("/v2/positions")
    JsonNode positions();

    @GetMapping("/v2/assets/{symbol}")
    JsonNode asset(@PathVariable("symbol") String symbol);

    /** Body: symbol, qty, side, type, time_in_force, client_order_id. */
    @PostMapping("/v2/orders")
    JsonNode submitOrder(@RequestBody Map<String, Object> order);

    @GetMapping("/v2/orders:by_client_order_id")
    JsonNode orderByClientId(@RequestParam("client_order_id") String clientOrderId);

    @GetMapping("/v2/orders/{id}")
    JsonNode order(@PathVariable("id") String id);

    @DeleteMapping("/v2/orders/{id}")
    void cancelOrder(@PathVariable("id") String id);

    @GetMapping("/v2/orders")
    JsonNode orders(@RequestParam("status") String status, @RequestParam("limit") int limit);

    // ---- market data (base URL passed per call) ----------------------------------------------------

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

        /** Never retry inside Feign: a retried order could be sent twice. Reads are retried by the caller. */
        @Bean
        Retryer feignRetryer() {
            return Retryer.NEVER_RETRY;
        }
    }
}
