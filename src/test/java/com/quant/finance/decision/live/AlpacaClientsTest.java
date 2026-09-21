package com.quant.finance.decision.live;

import com.quant.finance.decision.error.AlpacaApiException;
import com.quant.finance.decision.client.AlpacaCredentials;
import com.quant.finance.decision.live.AlpacaModels.*;
import com.quant.finance.decision.strategy.BarSeries;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The Alpaca clients against a real local HTTP server that answers like Alpaca does. */
class AlpacaClientsTest {

    record Req(String method, String path, String query, String body, Map<String, String> headers) {}

    private HttpServer server;
    private final List<Req> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Req, String[]>> routes = new HashMap<>();   // "GET /v2/account" -> [status, body]
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

    /** The real Feign clients (with their interceptors and error decoder) pointed at {@code trading} / {@code data}. */
    private FeignTestSupport feign(String trading, String data, String key, String secret) {
        FeignTestSupport f = new FeignTestSupport(trading, data, key, secret);
        contexts.add(f);
        return f;
    }

    private FeignTestSupport feign() { return feign(base, base, "PKTEST", "secret"); }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> h = new HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
        Req r = new Req(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(), body, h);
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

    private AlpacaTradingClient trading() { return new AlpacaTradingClient(feign().client(), base); }

    // ---- trading ---------------------------------------------------------------------------------

    @Test
    void readsTheAccountAndSendsTheKeys() {
        route("GET /v2/account", "200", """
                {"status":"ACTIVE","equity":"100523.45","cash":"50000","buying_power":"200000","long_market_value":"25000.5",
                 "short_market_value":"-1200","trading_blocked":false,"account_blocked":false,"shorting_enabled":true}""");
        AccountInfo a = trading().account();
        assertEquals("ACTIVE", a.status());
        assertEquals(100523.45, a.equity(), 1e-9);
        assertEquals(-1200, a.shortMarketValue(), 1e-9);
        assertTrue(a.shortingEnabled());
        assertFalse(a.tradingBlocked());
        Req r = requests.get(0);
        assertEquals("PKTEST", r.headers().get("apca-api-key-id"));
        assertEquals("secret", r.headers().get("apca-api-secret-key"));
    }

    @Test
    void readsTheClockPositionsAndAsset() {
        route("GET /v2/clock", "200", "{\"timestamp\":\"2026-09-21T10:15:32.123-04:00\",\"is_open\":true,"
                + "\"next_open\":\"2026-09-22T09:30:00-04:00\",\"next_close\":\"2026-09-21T16:00:00-04:00\"}");
        MarketClock c = trading().clock();
        assertTrue(c.open());
        assertEquals(Instant.parse("2026-09-21T14:15:32.123Z"), c.timestamp());
        assertEquals(Instant.parse("2026-09-22T13:30:00Z"), c.nextOpen());

        route("GET /v2/positions", "200", "[{\"symbol\":\"MU\",\"qty\":\"-12\",\"avg_entry_price\":\"90.5\",\"current_price\":\"88\","
                + "\"market_value\":\"-1056\",\"unrealized_pl\":\"30\"},{\"symbol\":\"AAOI\",\"qty\":\"5\",\"avg_entry_price\":\"20\","
                + "\"current_price\":\"21\",\"market_value\":\"105\",\"unrealized_pl\":\"5\"}]");
        List<PositionInfo> p = trading().positions();
        assertEquals(2, p.size());
        assertEquals(-12, p.get(0).qty(), 1e-9);
        assertEquals("AAOI", p.get(1).symbol());

        route("GET /v2/assets/MU", "200", "{\"symbol\":\"MU\",\"tradable\":true,\"shortable\":true,\"easy_to_borrow\":false,\"fractionable\":true,\"status\":\"active\"}");
        AssetInfo a = trading().asset("MU");
        assertTrue(a.tradable() && a.shortable() && a.fractionable());
        assertFalse(a.easyToBorrow());
    }

    @Test
    void submitsAWholeShareMarketDayOrderWithAClientId() {
        route("POST /v2/orders", "200", "{\"id\":\"abc-1\",\"client_order_id\":\"qe-7-MU-0\",\"symbol\":\"MU\",\"side\":\"buy\",\"qty\":\"12\","
                + "\"status\":\"accepted\",\"filled_qty\":\"0\",\"filled_avg_price\":null,\"submitted_at\":\"2026-09-21T14:20:00.5Z\"}");
        OrderInfo o = trading().submitMarketOrder("MU", 12, "buy", "qe-7-MU-0");
        assertEquals("abc-1", o.id());
        assertEquals("accepted", o.status());
        assertNull(o.filledAvgPrice());
        assertFalse(o.isTerminal());
        Req r = requests.get(0);
        assertEquals("POST", r.method());
        assertTrue(r.body().contains("\"symbol\":\"MU\"") && r.body().contains("\"qty\":\"12\"") && r.body().contains("\"side\":\"buy\"")
                && r.body().contains("\"type\":\"market\"") && r.body().contains("\"time_in_force\":\"day\"")
                && r.body().contains("\"client_order_id\":\"qe-7-MU-0\""), r.body());
    }

    @Test
    void parsesAFilledOrderAndKnowsWhenAnOrderIsFinished() {
        route("GET /v2/orders/abc-1", "200", "{\"id\":\"abc-1\",\"client_order_id\":\"c\",\"symbol\":\"MU\",\"side\":\"sell\",\"qty\":\"3\","
                + "\"status\":\"filled\",\"filled_qty\":\"3\",\"filled_avg_price\":\"91.2345\",\"submitted_at\":\"2026-09-21T14:20:00Z\","
                + "\"filled_at\":\"2026-09-21T14:20:01Z\"}");
        OrderInfo o = trading().order("abc-1");
        assertTrue(o.isFilled() && o.isTerminal());
        assertEquals(91.2345, o.filledAvgPrice(), 1e-9);
        assertEquals(3, o.filledQty(), 1e-9);
        assertNotNull(o.filledAt());
        assertTrue(new OrderInfo("i", "c", "S", "buy", 1, "rejected", 0, null, null, null).isTerminal());
        assertFalse(new OrderInfo("i", "c", "S", "buy", 1, "partially_filled", 0.5, 10.0, null, null).isTerminal());
    }

    @Test
    void alpacasErrorTextIsPassedOnAndAnUnknownClientIdIsEmpty() {
        route("POST /v2/orders", "403", "{\"code\":40310000,\"message\":\"insufficient buying power\"}");
        AlpacaApiException e = assertThrows(AlpacaApiException.class, () -> trading().submitMarketOrder("MU", 1, "buy", "c"));
        assertEquals(403, e.status());
        assertEquals("insufficient buying power", e.getMessage());
        assertFalse(e.transientFailure());
        assertEquals(1, requests.size(), "an order is never retried");

        route("GET /v2/orders:by_client_order_id", "404", "{\"message\":\"order not found\"}");
        assertTrue(trading().findByClientOrderId("nope").isEmpty());
    }

    @Test
    void readsAreRetriedOnAServerErrorButTheAnswerIsNotLost() {
        int[] calls = {0};
        routes.put("GET /v2/account", r -> calls[0]++ < 2 ? new String[] {"503", "{\"message\":\"busy\"}"}
                : new String[] {"200", "{\"status\":\"ACTIVE\",\"equity\":\"5\"}"});
        assertEquals(5, trading().account().equity(), 1e-9);
        assertEquals(3, requests.size());
    }

    @Test
    void aReadThatKeepsFailingGivesUpWithAlpacasMessage() {
        route("GET /v2/account", "500", "{\"message\":\"internal\"}");
        AlpacaApiException e = assertThrows(AlpacaApiException.class, () -> trading().account());
        assertEquals(500, e.status());
        assertEquals(3, requests.size());
    }

    @Test
    void cancellingAnAlreadyFinishedOrderIsNotAnError() {
        route("DELETE /v2/orders/gone", "422", "{\"message\":\"order is not cancelable\"}");
        assertDoesNotThrow(() -> trading().cancelOrder("gone"));
        route("DELETE /v2/orders/boom", "500", "{\"message\":\"x\"}");
        assertThrows(AlpacaApiException.class, () -> trading().cancelOrder("boom"));
    }

    @Test
    void listsOpenOrders() {
        route("GET /v2/orders", "200", "[{\"id\":\"1\",\"client_order_id\":\"qe-1-A-0\",\"symbol\":\"A\",\"side\":\"buy\",\"qty\":\"2\",\"status\":\"new\",\"filled_qty\":\"0\"}]");
        List<OrderInfo> open = trading().openOrders();
        assertEquals(1, open.size());
        assertEquals("qe-1-A-0", open.get(0).clientOrderId());
        assertTrue(requests.get(0).query().contains("status=open"));
    }

    // ---- the paper-only guard ---------------------------------------------------------------------

    @Test
    void neverTradesAgainstAnythingButThePaperEndpoint() {
        for (String live : List.of("https://api.alpaca.markets", "https://example.com", "http://api.alpaca.markets:443", "not a url")) {
            AlpacaTradingClient c = new AlpacaTradingClient(feign().client(), live);
            assertFalse(c.isPaper(), live);
            assertThrows(IllegalStateException.class, () -> c.submitMarketOrder("MU", 1, "buy", "c"), live);
            assertThrows(IllegalStateException.class, c::account, live);
        }
        assertTrue(new AlpacaTradingClient(feign().client(), "https://paper-api.alpaca.markets").isPaper());
        assertTrue(requests.isEmpty(), "a refused call must not even leave the machine");
    }

    @Test
    void theFeignClientItselfRefusesANonPaperTargetBeforeSendingAnything() {
        // the same rule at the transport layer: even calling the client directly cannot reach a live-money host
        FeignTestSupport live = feign("https://api.alpaca.markets", base, "PKTEST", "secret");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> live.client().account());
        assertTrue(e.getMessage().contains("not the paper-trading endpoint"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> live.client().submitOrder(Map.of("symbol", "MU")));
        assertTrue(requests.isEmpty());
    }

    @Test
    void marketDataGoesToTheUrlPassedWithTheCallNotToTheTradingTarget() {
        // the client's fixed url is the (unreachable here) paper host; data calls carry their own base URL
        route("GET /v2/stocks/snapshots", "200", "{\"MU\":{\"latestTrade\":{\"p\":1}}}");
        FeignTestSupport f = feign("https://paper-api.alpaca.markets", base, "PKTEST", "secret");
        assertEquals(1.0, new AlpacaLiveData(f.client(), creds, base).latestPrices(List.of("MU")).get("MU"));
        assertEquals(1, requests.size());
        assertEquals("PKTEST", requests.get(0).headers().get("apca-api-key-id"), "the keys are sent to the data host too");
    }

    @Test
    void marketDataStillWorksWhenTheTradingTargetIsNotPaperButTradingDoesNot() {
        route("GET /v2/stocks/snapshots", "200", "{\"MU\":{\"latestTrade\":{\"p\":2}}}");
        FeignTestSupport f = feign("https://api.alpaca.markets", base, "PKTEST", "secret");
        assertEquals(2.0, new AlpacaLiveData(f.client(), creds, base).latestPrices(List.of("MU")).get("MU"));
        assertThrows(IllegalStateException.class, () -> f.client().account());
        assertEquals(1, requests.size(), "only the data request went out");
    }

    @Test
    void theAccountKeysAreNeverSentToAHostThatIsNotAlpacas() {
        FeignTestSupport f = feign();
        for (String evil : List.of("https://evil.example", "https://api.alpaca.markets", "https://paper-api.alpaca.markets")) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> f.client().snapshots(java.net.URI.create(evil), "MU", "iex"), evil);
            assertTrue(e.getMessage().contains("not Alpaca's market-data host"), e.getMessage());
        }
        assertTrue(requests.isEmpty(), "nothing left the machine");
    }

    @Test
    void aTradingRequestCannotBeAimedAtTheDataHostEither() {
        // a POST / account read must use the paper host even though a data host is also a real Alpaca host
        FeignTestSupport f = feign("https://data.alpaca.markets", base, "PKTEST", "secret");
        assertThrows(IllegalStateException.class, () -> f.client().submitOrder(Map.of("symbol", "MU")));
        assertThrows(IllegalStateException.class, () -> f.client().account());
        assertTrue(requests.isEmpty());
    }

    @Test
    void aServerThatCannotBeReachedIsReportedAsSuchAndReadsAreRetried() {
        AlpacaTradingClient c = trading();
        server.stop(0);
        long t0 = System.currentTimeMillis();
        AlpacaApiException e = assertThrows(AlpacaApiException.class, c::account);
        assertEquals(0, e.status());
        assertTrue(e.getMessage().startsWith("Could not reach Alpaca"), e.getMessage());
        assertTrue(e.transientFailure());
        assertTrue(System.currentTimeMillis() - t0 >= 1000, "three attempts with a pause between them");
        assertThrows(AlpacaApiException.class, () -> c.submitMarketOrder("MU", 1, "buy", "c"));
    }

    @Test
    void refusesToSendAnOrderForANonsensicalQuantityOrSide() {
        assertThrows(IllegalArgumentException.class, () -> trading().submitMarketOrder("MU", 0, "buy", "c"));
        assertThrows(IllegalArgumentException.class, () -> trading().submitMarketOrder("MU", 1, "short", "c"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void missingCredentialsFailWithAClearMessage() {
        AlpacaTradingClient c = new AlpacaTradingClient(feign(base, base, "", "").client(), base);
        IllegalStateException e = assertThrows(IllegalStateException.class, c::account);
        assertTrue(e.getMessage().contains("credentials"), e.getMessage());
        assertTrue(requests.isEmpty());
    }

    // ---- market data -------------------------------------------------------------------------------

    @Test
    void fetchesBarsPageByPageWithSplitAdjustment() {
        int[] page = {0};
        routes.put("GET /v2/stocks/MU/bars", r -> page[0]++ == 0
                ? new String[] {"200", "{\"bars\":[{\"t\":\"2026-09-21T14:00:00Z\",\"o\":1,\"h\":2,\"l\":0.5,\"c\":1.5,\"v\":100}],\"next_page_token\":\"p2\"}"}
                : new String[] {"200", "{\"bars\":[{\"t\":\"2026-09-21T14:01:00Z\",\"o\":1.5,\"h\":2,\"l\":1,\"c\":1.8,\"v\":50}],\"next_page_token\":null}"});
        AlpacaLiveData d = new AlpacaLiveData(feign().client(), creds, base);
        BarSeries s = d.bars("MU", LiveDataSource.Base.MIN1, 3);
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
        Map<String, Double> p = new AlpacaLiveData(feign().client(), creds, base).latestPrices(List.of("MU", "AAOI", "XYZ", "NOPE"));
        assertEquals(91.5, p.get("MU"));
        assertEquals(20.25, p.get("AAOI"));
        assertFalse(p.containsKey("XYZ"));
        assertFalse(p.containsKey("NOPE"));
        assertTrue(requests.get(0).query().contains("symbols=MU,AAOI,XYZ,NOPE") || requests.get(0).query().contains("symbols=MU%2CAAOI"), requests.get(0).query());

        route("GET /v2/stocks/snapshots", "200", "{\"snapshots\":{\"MU\":{\"latestTrade\":{\"p\":10}}}}");
        assertEquals(10.0, new AlpacaLiveData(feign().client(), creds, base).latestPrices(List.of("MU")).get("MU"), "the older response shape is understood too");
    }
}
