package com.quant.finance.decision.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Small JSON-over-HTTP helper for Alpaca: auth headers, timeouts, Alpaca's error text, and retries for reads. */
final class AlpacaHttp {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int READ_ATTEMPTS = 3;

    private final RestClient rest;
    private final AlpacaCredentials creds;

    AlpacaHttp(String baseUrl, AlpacaCredentials creds) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(30_000);
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
        this.creds = creds;
    }

    /** GET, retried on rate limits, server errors and network failures. */
    JsonNode get(String path, Map<String, ?> query, Object... vars) {
        AlpacaApiException last = null;
        for (int attempt = 1; attempt <= READ_ATTEMPTS; attempt++) {
            try {
                return send(HttpMethod.GET, path, null, query, vars);
            } catch (AlpacaApiException e) {
                last = e;
                if (!e.transientFailure() || attempt == READ_ATTEMPTS) throw e;
                pause(400L * attempt);
            }
        }
        throw last;
    }

    /** One attempt, no retry — used for anything that changes state. */
    JsonNode send(HttpMethod method, String path, Object body, Map<String, ?> query, Object... vars) {
        if (!creds.configured())
            throw new IllegalStateException("Alpaca credentials are not configured: set decision.alpaca.api-key-id / "
                    + "api-secret-key (env ALPACA_API_KEY_ID / ALPACA_API_SECRET_KEY).");
        try {
            RestClient.RequestBodySpec spec = rest.method(method)
                    .uri(u -> {
                        u.path(path);
                        if (query != null) query.forEach((k, v) -> { if (v != null) u.queryParam(k, v); });
                        return u.build(vars);
                    })
                    .header("APCA-API-KEY-ID", creds.keyId())
                    .header("APCA-API-SECRET-KEY", creds.secret());
            if (body != null) spec = spec.body(body).header("Content-Type", "application/json");
            return spec.exchange((request, response) -> {
                int status = response.getStatusCode().value();
                String text = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                if (status >= 400) throw new AlpacaApiException(status, errorText(status, text));
                return text.isBlank() ? MAPPER.missingNode() : MAPPER.readTree(text);
            });
        } catch (AlpacaApiException e) {
            throw e;
        } catch (RestClientException e) {
            throw new AlpacaApiException("Could not reach Alpaca: " + e.getMessage(), e);
        }
    }

    /** Alpaca's errors are {"code":40310000,"message":"insufficient buying power"}. */
    static String errorText(int status, String body) {
        try {
            JsonNode n = MAPPER.readTree(body);
            String m = n.path("message").asText("");
            if (!m.isBlank()) return m;
        } catch (Exception ignored) { /* not JSON */ }
        return "HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body.substring(0, Math.min(200, body.length())));
    }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
