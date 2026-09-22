package com.quant.finance.decision.client;

import com.quant.finance.decision.error.AlpacaApiException;
import feign.Request;
import feign.RequestInterceptor;
import feign.Response;
import feign.Util;
import feign.codec.ErrorDecoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** What the Alpaca Feign client needs: authentication, the data-host guard, error decoding and timeouts. */
public final class AlpacaFeign {
    private AlpacaFeign() {}

    /** Alpaca's market-data host — the only real host a market-data request may go to. */
    public static final String DATA_HOST = "data.alpaca.markets";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** True when {@code url} points at Alpaca's market-data host (or localhost, which the tests use). */
    public static boolean isDataUrl(String url) {
        try {
            String host = URI.create(url == null ? "" : url.trim()).getHost();
            return host != null && (host.equalsIgnoreCase(DATA_HOST) || LOCAL_HOSTS.contains(host.toLowerCase()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Adds the Alpaca key headers; refuses to send anything when no credentials are configured. */
    public static RequestInterceptor authInterceptor(AlpacaCredentials creds) {
        return template -> {
            if (!creds.configured())
                throw new IllegalStateException("Alpaca credentials are not configured: set decision.alpaca.api-key-id / "
                        + "api-secret-key (env ALPACA_API_KEY_ID / ALPACA_API_SECRET_KEY).");
            template.header("APCA-API-KEY-ID", creds.keyId());
            template.header("APCA-API-SECRET-KEY", creds.secret());
        };
    }

    /** Refuses a request headed anywhere but Alpaca's market-data host, before it leaves the machine. */
    public static RequestInterceptor hostGuardInterceptor() {
        return template -> {
            String path = template.path() == null ? "" : template.path();
            boolean overridden = path.startsWith("http://") || path.startsWith("https://");   // a per-call base URL
            String base = overridden ? path : template.feignTarget() == null ? "" : template.feignTarget().url();
            if (!isDataUrl(base))
                throw new IllegalStateException("Refusing to send a market-data request to '" + hostOf(base)
                        + "': it is not Alpaca's market-data host (https://" + DATA_HOST + ").");
        };
    }

    private static String hostOf(String url) {
        try { String h = URI.create(url).getHost(); return h == null ? url : h; } catch (IllegalArgumentException e) { return url; }
    }

    /** Turns an HTTP error into {@link AlpacaApiException} carrying Alpaca's own message. */
    public static ErrorDecoder errorDecoder() {
        return (methodKey, response) -> new AlpacaApiException(response.status(), errorText(response.status(), bodyOf(response)));
    }

    /** Connect and read timeouts. */
    public static Request.Options options() {
        return new Request.Options(10, TimeUnit.SECONDS, 30, TimeUnit.SECONDS, true);
    }

    /** Alpaca's errors are {"code":40310000,"message":"insufficient buying power"}. */
    static String errorText(int status, String body) {
        try {
            JsonNode n = MAPPER.readTree(body == null ? "" : body);
            String m = n.path("message").asText("");
            if (!m.isBlank()) return m;
        } catch (Exception ignored) { /* not JSON */ }
        return "HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body.substring(0, Math.min(200, body.length())));
    }

    private static String bodyOf(Response response) {
        if (response.body() == null) return "";
        try {
            return new String(Util.toByteArray(response.body().asInputStream()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
