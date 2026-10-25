package com.quant.finance.decision.data;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.quant.finance.decision.config.ApplicationProperties;
import com.quant.finance.decision.entity.PriceBarEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Alpaca Market Data bars (needs an Alpaca key pair, paper is fine: application.alpaca.api-key-id / api-secret-key).
 * {@code application.alpaca.timeframe} goes straight to Alpaca's bars API ("1Day" default, "1Week", "1Hour", "1Min" …).
 */
@Component
@Slf4j
public class AlpacaMarketDataClient implements MarketDataClient {

    private final ApplicationProperties properties;
    private final RestClient restClient;

    public AlpacaMarketDataClient(ApplicationProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getAlpaca().getDataBaseUrl())
                .build();
    }

    @Override
    public List<PriceBarEntity> fetchHistory(String symbol) {
        Instant end = Instant.now();
        return fetch(symbol, end.minusSeconds(Math.round(properties.getDefaultHistoryYears() * 365.25 * 86400)), end);
    }

    /** Incremental fetch for polling — only bars from {@code since} to now (one or two requests). */
    @Override
    public List<PriceBarEntity> fetchHistory(String symbol, Instant since) {
        Instant end = Instant.now();
        Instant start = since != null
                ? since
                : end.minusSeconds(Math.round(properties.getDefaultHistoryYears() * 365.25 * 86400));
        return fetch(symbol, start, end);
    }

    private List<PriceBarEntity> fetch(String symbol, Instant start, Instant end) {
        ApplicationProperties.Alpaca alpaca = properties.getAlpaca();
        if (StringUtils.isBlank(alpaca.getApiKeyId()) || StringUtils.isBlank(alpaca.getApiSecretKey())) {
            throw new IllegalStateException(
                    "Alpaca data source selected but no credentials configured. Set "
                            + "application.alpaca.api-key-id / api-secret-key (env ALPACA_API_KEY_ID / ALPACA_API_SECRET_KEY).");
        }

        // keyed by bar time to dedupe: Alpaca can hand back the same bar twice across a page
        // boundary, and price_bar's (symbol, bar_time) unique constraint turns that into a
        // hard insert failure downstream if it isn't collapsed here first.
        String timeframe = alpaca.getTimeframe();
        Map<Instant, PriceBarEntity> byTime = new LinkedHashMap<>();
        String pageToken = null;
        do {
            AlpacaBarsResponse page = fetchPage(symbol, start, end, pageToken);
            if (page == null || page.bars() == null) {
                break;
            }
            for (AlpacaBar b : page.bars()) {
                Instant barTime = Instant.parse(b.t());
                byTime.put(
                        barTime,
                        new PriceBarEntity(symbol, barTime, b.o(), b.h(), b.l(), b.c(), b.v(), "alpaca", timeframe));
            }
            pageToken = page.nextPageToken();
        } while (pageToken != null);

        List<PriceBarEntity> out = new ArrayList<>(byTime.values());
        log.info(
                "Fetched {} {} bars for {} from Alpaca ({} feed, since {})",
                out.size(),
                timeframe,
                symbol,
                alpaca.getFeed(),
                start);
        return out;
    }

    private AlpacaBarsResponse fetchPage(String symbol, Instant start, Instant end, String pageToken) {
        ApplicationProperties.Alpaca alpaca = properties.getAlpaca();
        return restClient
                .get()
                .uri(uri -> uri.path("/v2/stocks/{symbol}/bars")
                        .queryParam("timeframe", alpaca.getTimeframe())
                        .queryParam("start", start)
                        .queryParam("end", end)
                        .queryParam("limit", 10000)
                        .queryParam("adjustment", "raw")
                        .queryParam("feed", alpaca.getFeed())
                        .queryParamIfPresent("page_token", Optional.ofNullable(pageToken))
                        .build(symbol))
                .header("APCA-API-KEY-ID", alpaca.getApiKeyId())
                .header("APCA-API-SECRET-KEY", alpaca.getApiSecretKey())
                .retrieve()
                .body(AlpacaBarsResponse.class);
    }

    @Override
    public String source() {
        return "alpaca";
    }

    @Override
    public String configuredTimeframe() {
        return properties.getAlpaca().getTimeframe();
    }

    private record AlpacaBar(
            @JsonProperty("t") String t,
            @JsonProperty("o") double o,
            @JsonProperty("h") double h,
            @JsonProperty("l") double l,
            @JsonProperty("c") double c,
            @JsonProperty("v") double v) {}

    private record AlpacaBarsResponse(
            @JsonProperty("bars") List<AlpacaBar> bars,
            @JsonProperty("next_page_token") String nextPageToken) {}
}
