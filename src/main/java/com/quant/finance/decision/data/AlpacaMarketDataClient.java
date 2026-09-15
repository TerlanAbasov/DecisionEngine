package com.quant.finance.decision.data;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.quant.finance.decision.domain.PriceBarEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Alpaca Market Data (https://data.alpaca.markets) bars. Needs an Alpaca account
 * (paper is fine): set decision.alpaca.api-key-id / api-secret-key (env
 * ALPACA_API_KEY_ID / ALPACA_API_SECRET_KEY).
 *
 * <p>{@code decision.alpaca.timeframe} is passed straight through to Alpaca's bars API,
 * so it accepts any of Alpaca's timeframe strings: "1Day" (daily, default), "1Week" (weekly),
 * "1Hour" (hourly), or minute bars like "1Min" / "5Min" / "15Min" if you need those.
 */
@Component
@Slf4j
public class AlpacaMarketDataClient implements MarketDataClient {

    @Value("${decision.alpaca.api-key-id:}")
    private String apiKeyId;
    @Value("${decision.alpaca.api-secret-key:}")
    private String apiSecretKey;
    @Value("${decision.alpaca.feed:iex}")
    private String feed;
    @Value("${decision.alpaca.timeframe:1Day}")
    private String timeframe;
    @Value("${decision.default-history-years:6.5}")
    private double years;

    private final RestClient restClient;

    public AlpacaMarketDataClient(
            @Value("${decision.alpaca.data-base-url:https://data.alpaca.markets}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public List<PriceBarEntity> fetchHistory(String symbol) {
        Instant end = Instant.now();
        return fetch(symbol, end.minusSeconds(Math.round(years * 365.25 * 86400)), end);
    }

    /** Incremental fetch for polling — only bars from {@code since} to now (one or two requests). */
    @Override
    public List<PriceBarEntity> fetchHistory(String symbol, Instant since) {
        Instant end = Instant.now();
        Instant start = since != null ? since : end.minusSeconds(Math.round(years * 365.25 * 86400));
        return fetch(symbol, start, end);
    }

    private List<PriceBarEntity> fetch(String symbol, Instant start, Instant end) {
        if (apiKeyId.isBlank() || apiSecretKey.isBlank()) {
            throw new IllegalStateException(
                "Alpaca data source selected but no credentials configured. Set "
                + "decision.alpaca.api-key-id / api-secret-key (env ALPACA_API_KEY_ID / ALPACA_API_SECRET_KEY).");
        }

        List<PriceBarEntity> out = new ArrayList<>();
        String pageToken = null;
        do {
            AlpacaBarsResponse page = fetchPage(symbol, start, end, pageToken);
            if (page == null || page.bars() == null) break;
            for (AlpacaBar b : page.bars()) {
                Instant barTime = Instant.parse(b.t());
                out.add(new PriceBarEntity(symbol, barTime, b.o(), b.h(), b.l(), b.c(), b.v(), "alpaca", timeframe));
            }
            pageToken = page.nextPageToken();
        } while (pageToken != null);

        log.info("Fetched {} {} bars for {} from Alpaca ({} feed, since {})", out.size(), timeframe, symbol, feed, start);
        return out;
    }

    private AlpacaBarsResponse fetchPage(String symbol, Instant start, Instant end, String pageToken) {
        return restClient.get()
                .uri(uri -> uri.path("/v2/stocks/{symbol}/bars")
                        .queryParam("timeframe", timeframe)
                        .queryParam("start", start)
                        .queryParam("end", end)
                        .queryParam("limit", 10000)
                        .queryParam("adjustment", "raw")
                        .queryParam("feed", feed)
                        .queryParamIfPresent("page_token", Optional.ofNullable(pageToken))
                        .build(symbol))
                .header("APCA-API-KEY-ID", apiKeyId)
                .header("APCA-API-SECRET-KEY", apiSecretKey)
                .retrieve()
                .body(AlpacaBarsResponse.class);
    }

    @Override
    public String source() {
        return "alpaca";
    }

    @Override
    public String configuredTimeframe() {
        return timeframe;
    }

    private record AlpacaBar(
            @JsonProperty("t") String t,
            @JsonProperty("o") double o,
            @JsonProperty("h") double h,
            @JsonProperty("l") double l,
            @JsonProperty("c") double c,
            @JsonProperty("v") double v) {
    }

    private record AlpacaBarsResponse(
            @JsonProperty("bars") List<AlpacaBar> bars,
            @JsonProperty("next_page_token") String nextPageToken) {
    }
}
