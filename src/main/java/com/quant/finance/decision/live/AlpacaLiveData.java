package com.quant.finance.decision.live;

import com.quant.finance.decision.client.AlpacaCredentials;
import com.quant.finance.decision.client.AlpacaClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.quant.finance.decision.strategy.BarSeries;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alpaca market data for the paper-trading job: recent bars (in memory, topped up incrementally) and latest prices. Bars are split-adjusted,
 * unlike the raw cached history the backtests use, so a split inside the lookback can't distort the indicators.
 */
@Component
@Slf4j
public class AlpacaLiveData implements LiveDataSource {

    private final AlpacaClient client;
    private final AlpacaCredentials creds;
    private final URI dataUrl;
    private final Map<String, BarWindow> windows = new ConcurrentHashMap<>();

    public AlpacaLiveData(AlpacaClient client, AlpacaCredentials creds,
                          @Value("${decision.alpaca.data-base-url:https://data.alpaca.markets}") String dataBaseUrl) {
        this.client = client;
        this.creds = creds;
        this.dataUrl = URI.create(dataBaseUrl.trim());
    }

    @Override
    public BarSeries bars(String symbol, Base base, int lookbackDays) {
        BarWindow w = windows.computeIfAbsent(symbol + "|" + base, k -> new BarWindow());
        synchronized (w) {
            return w.update(symbol, Duration.ofDays(Math.max(1, lookbackDays)), Instant.now(),
                    (from, to) -> fetch(symbol, base, from, to));
        }
    }

    private Map<Instant, BarWindow.Bar> fetch(String symbol, Base base, Instant from, Instant to) {
        Map<Instant, BarWindow.Bar> out = new LinkedHashMap<>();
        String pageToken = null;
        do {
            String token = pageToken;
            JsonNode page = AlpacaCalls.read(() -> client.bars(dataUrl, symbol, base.alpacaTimeframe, from.toString(), to.toString(),
                    10_000, "split", creds.feed(), token));
            for (JsonNode b : page.path("bars"))
                out.put(Instant.parse(b.path("t").asText()),
                        new BarWindow.Bar(b.path("o").asDouble(), b.path("h").asDouble(), b.path("l").asDouble(),
                                b.path("c").asDouble(), b.path("v").asDouble()));
            String next = page.path("next_page_token").asText("");
            pageToken = next.isBlank() || "null".equals(next) ? null : next;
        } while (pageToken != null);
        return out;
    }

    @Override
    public Map<String, Double> latestPrices(Collection<String> symbols) {
        Map<String, Double> out = new HashMap<>();
        if (symbols.isEmpty()) return out;
        JsonNode root = AlpacaCalls.read(() -> client.snapshots(dataUrl, String.join(",", symbols), creds.feed()));
        JsonNode snaps = root.has("snapshots") ? root.path("snapshots") : root;   // both response shapes exist
        for (String s : symbols) {
            JsonNode n = snaps.path(s);
            double p = n.path("latestTrade").path("p").asDouble(0);
            if (p <= 0) p = n.path("minuteBar").path("c").asDouble(0);
            if (p <= 0) p = n.path("dailyBar").path("c").asDouble(0);
            if (p > 0) out.put(s, p);
        }
        return out;
    }
}
