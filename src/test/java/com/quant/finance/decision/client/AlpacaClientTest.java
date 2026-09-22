package com.quant.finance.decision.client;

import com.quant.finance.decision.autotrade.AlpacaLiveData;
import com.quant.finance.decision.error.AlpacaApiException;
import com.quant.finance.decision.strategy.BarSeries;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** {@link AlpacaClient} (Alpaca's market-data API) against a real local HTTP server that answers like Alpaca does. */
class AlpacaClientTest {

    record Req(String method, String path, String query, Map<String, String> headers) {}

    private HttpServer server;
    private final List<Req> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Req, String[]>> routes = new HashMap<>();   // "GET /v2/stocks/MU/bars" -> [status, body]
    private String base;
    private final AlpacaCredentials creds = new AlpacaCredentials("PKTEST", "secret", "iex");
    private final List<FeignTestSupport> contexts = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        contexts.forEach(FeignTestSupport::close);
        server.stop(0);
    }

    /** The real Feign client (with its interceptors and error decoder) pointed at {@code dataBase}. */
    private FeignTestSupport feign(String dataBase, String key, String secret) {
        FeignTestSupport f = new FeignTestSupport(dataBase, key, secret);
        contexts.add(f);
        return f;
    }

    private FeignTestSupport feign() { return feign(base, "PKTEST", "secret"); }

    private void handle(HttpExchange ex) throws IOException {
        ex.getRequestBody().readAllBytes();
        Map<String, String> h = new HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
        Req r = new Req(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(), h);
        requests.add(r);
        Function<Req, String[]> route = routes.get(r.method() + " " + r.path());
        String[] resp = route == null ? new String[] {"404", "{\"message\":\"no route\"}"} : route.apply(r);
        byte[] out = resp[1].getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(Integer.parseInt(resp[0]), out.length == 0 ? -1 : out.length);
        if (out.length > 0) ex.getResponseBody().write(out);
        ex.close();
    }

    private void route(String key, String status, String body) { routes.put(key, r -> new String[] {status, body}); }

    private AlpacaLiveData data() { return new AlpacaLiveData(feign().client(), creds, base); }

    @Test
    void readsTheAccountKeysWithEveryRequest() {
        route("GET /v2/stocks/snapshots", "200", "{\"MU\":{\"latestTrade\":{\"p\":1}}}");
        data().latestPrices(List.of("MU"));
        assertEquals("PKTEST", requests.get(0).headers().get("apca-api-key-id"));
        assertEquals("secret", requests.get(0).headers().get("apca-api-secret-key"));
    }

    @Test
    void fetchesBarsPageByPageWithSplitAdjustment() {
        int[] page = {0};
        routes.put("GET /v2/stocks/MU/bars", r -> page[0]++ == 0
                ? new String[] {"200", "{\"bars\":[{\"t\":\"2026-09-21T14:00:00Z\",\"o\":1,\"h\":2,\"l\":0.5,\"c\":1.5,\"v\":100}],\"next_page_token\":\"p2\"}"}
                : new String[] {"200", "{\"bars\":[{\"t\":\"2026-09-21T14:01:00Z\",\"o\":1.5,\"h\":2,\"l\":1,\"c\":1.8,\"v\":50}],\"next_page_token\":null}"});
        BarSeries s = data().bars("MU", com.quant.finance.decision.autotrade.LiveDataSource.Base.MIN1, 3);
        assertEquals(2, s.size());
        assertEquals(1.8, s.close[1], 1e-9);
        assertEquals(Instant.parse("2026-09-21T14:01:00Z"), s.date[1]);
        String q1 = requests.get(0).query(), q2 = requests.get(1).query();
        assertTrue(q1.contains("timeframe=1Min") && q1.contains("adjustment=split") && q1.contains("feed=iex"), q1);
        assertTrue(q2.contains("page_token=p2"), q2);
    }

    @Test
    void latestPricesReadTheTradeThenFallBackToTheBar() {
        route("GET /v2/stocks/snapshots", "200", "{\"MU\":{\"latestTrade\":{\"p\":91.5}},\"AAOI\":{\"minuteBar\":{\"c\":20.25}},\"XYZ\":{}}");
        Map<String, Double> p = data().latestPrices(List.of("MU", "AAOI", "XYZ", "NOPE"));
        assertEquals(91.5, p.get("MU"));
        assertEquals(20.25, p.get("AAOI"));
        assertFalse(p.containsKey("XYZ"));
        assertFalse(p.containsKey("NOPE"));

        route("GET /v2/stocks/snapshots", "200", "{\"snapshots\":{\"MU\":{\"latestTrade\":{\"p\":10}}}}");
        assertEquals(10.0, data().latestPrices(List.of("MU")).get("MU"), "the older response shape is understood too");
    }

    @Test
    void readsAreRetriedOnAServerErrorButTheAnswerIsNotLost() {
        int[] calls = {0};
        routes.put("GET /v2/stocks/snapshots", r -> calls[0]++ < 2 ? new String[] {"503", "{\"message\":\"busy\"}"}
                : new String[] {"200", "{\"MU\":{\"latestTrade\":{\"p\":5}}}"});
        assertEquals(5.0, data().latestPrices(List.of("MU")).get("MU"));
        assertEquals(3, requests.size());
    }

    @Test
    void aReadThatKeepsFailingGivesUpWithAlpacasMessage() {
        route("GET /v2/stocks/snapshots", "500", "{\"message\":\"internal\"}");
        AlpacaApiException e = assertThrows(AlpacaApiException.class, () -> data().latestPrices(List.of("MU")));
        assertEquals(500, e.status());
        assertEquals(3, requests.size());
    }

    @Test
    void aServerThatCannotBeReachedIsReportedAsSuchAndReadsAreRetried() {
        AlpacaLiveData d = data();
        server.stop(0);
        long t0 = System.currentTimeMillis();
        AlpacaApiException e = assertThrows(AlpacaApiException.class, () -> d.latestPrices(List.of("MU")));
        assertEquals(0, e.status());
        assertTrue(e.getMessage().startsWith("Could not reach Alpaca"), e.getMessage());
        assertTrue(e.transientFailure());
        assertTrue(System.currentTimeMillis() - t0 >= 1000, "three attempts with a pause between them");
    }

    // ---- the market-data-only host guard ------------------------------------------------------------

    @Test
    void everyCallMustGoToAlpacasDataHostNotWherever() {
        FeignTestSupport f = feign();
        for (String evil : List.of("https://evil.example", "https://api.alpaca.markets", "https://paper-api.alpaca.markets")) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> f.client().snapshots(URI.create(evil), "MU", "iex"), evil);
            assertTrue(e.getMessage().contains("not Alpaca's market-data host"), e.getMessage());
        }
        assertTrue(requests.isEmpty(), "nothing left the machine");
    }

    @Test
    void theFeignClientRefusesToSendToTheWrongHostEvenWhenTheDeclaredTargetIsElsewhere() {
        // AlpacaLiveData always passes its own base URL, so the client's declared @FeignClient url never matters in practice —
        // the guard is what actually decides, and it must accept the real data host regardless of that declared url.
        FeignTestSupport f = feign("https://api.alpaca.markets", "PKTEST", "secret");
        assertThrows(IllegalStateException.class, () -> f.client().snapshots(URI.create("https://api.alpaca.markets"), "MU", "iex"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void missingCredentialsFailWithAClearMessage() {
        AlpacaLiveData d = new AlpacaLiveData(feign(base, "", "").client(), new AlpacaCredentials("", "", "iex"), base);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> d.latestPrices(List.of("MU")));
        assertTrue(e.getMessage().contains("credentials"), e.getMessage());
        assertTrue(requests.isEmpty());
    }
}
