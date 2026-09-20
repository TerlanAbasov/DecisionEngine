package com.quant.finance.decision.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.quant.finance.decision.live.AlpacaModels.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Alpaca's trading API, <b>paper account only</b>. Every call first checks that the configured endpoint is
 * Alpaca's paper host (or localhost, for tests) and refuses otherwise, so a mistyped or copied base URL can
 * never send an order to a live-money account.
 */
@Component
@Slf4j
public class AlpacaTradingClient implements TradingGateway {

    static final String PAPER_HOST = "paper-api.alpaca.markets";
    private static final Set<String> ALLOWED_HOSTS = Set.of(PAPER_HOST, "localhost", "127.0.0.1");

    private final String baseUrl;
    private final AlpacaHttp http;

    @Autowired
    public AlpacaTradingClient(AlpacaCredentials creds,
                               @Value("${decision.alpaca.trading-base-url:https://paper-api.alpaca.markets}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.http = new AlpacaHttp(this.baseUrl, creds);
    }

    @Override
    public boolean isPaper() {
        try {
            String host = URI.create(baseUrl).getHost();
            return host != null && ALLOWED_HOSTS.contains(host.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void requirePaper() {
        if (!isPaper())
            throw new IllegalStateException("Refusing to trade: decision.alpaca.trading-base-url is '" + baseUrl
                    + "', which is not the paper-trading endpoint (https://" + PAPER_HOST + "). "
                    + "This job only ever trades a paper (demo) account.");
    }

    @Override
    public AccountInfo account() {
        requirePaper();
        JsonNode n = http.get("/v2/account", null);
        return new AccountInfo(n.path("status").asText(""), num(n, "equity"), num(n, "cash"), num(n, "buying_power"),
                num(n, "long_market_value"), num(n, "short_market_value"),
                n.path("trading_blocked").asBoolean(false), n.path("account_blocked").asBoolean(false),
                n.path("shorting_enabled").asBoolean(false));
    }

    @Override
    public MarketClock clock() {
        requirePaper();
        JsonNode n = http.get("/v2/clock", null);
        return new MarketClock(n.path("is_open").asBoolean(false), instant(n, "timestamp"),
                instant(n, "next_open"), instant(n, "next_close"));
    }

    @Override
    public List<PositionInfo> positions() {
        requirePaper();
        List<PositionInfo> out = new ArrayList<>();
        for (JsonNode p : http.get("/v2/positions", null))
            out.add(new PositionInfo(p.path("symbol").asText(), num(p, "qty"), num(p, "avg_entry_price"),
                    num(p, "current_price"), num(p, "market_value"), num(p, "unrealized_pl")));
        return out;
    }

    @Override
    public AssetInfo asset(String symbol) {
        requirePaper();
        JsonNode n = http.get("/v2/assets/{symbol}", null, symbol);
        return new AssetInfo(n.path("symbol").asText(symbol), n.path("tradable").asBoolean(false),
                n.path("shortable").asBoolean(false), n.path("easy_to_borrow").asBoolean(false),
                n.path("fractionable").asBoolean(false), n.path("status").asText(""));
    }

    @Override
    public OrderInfo submitMarketOrder(String symbol, long qty, String side, String clientOrderId) {
        requirePaper();
        if (qty <= 0) throw new IllegalArgumentException("order quantity must be positive, got " + qty);
        if (!side.equals("buy") && !side.equals("sell")) throw new IllegalArgumentException("side must be buy or sell");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", symbol);
        body.put("qty", Long.toString(qty));
        body.put("side", side);
        body.put("type", "market");
        body.put("time_in_force", "day");
        body.put("client_order_id", clientOrderId);
        log.info("Alpaca paper order: {} {} x{} ({})", side, symbol, qty, clientOrderId);
        return order(http.send(HttpMethod.POST, "/v2/orders", body, null));
    }

    @Override
    public Optional<OrderInfo> findByClientOrderId(String clientOrderId) {
        requirePaper();
        try {
            return Optional.of(order(http.get("/v2/orders:by_client_order_id", Map.of("client_order_id", clientOrderId))));
        } catch (AlpacaApiException e) {
            if (e.status() == 404) return Optional.empty();
            throw e;
        }
    }

    @Override
    public OrderInfo order(String orderId) {
        requirePaper();
        return order(http.get("/v2/orders/{id}", null, orderId));
    }

    @Override
    public void cancelOrder(String orderId) {
        requirePaper();
        try {
            http.send(HttpMethod.DELETE, "/v2/orders/{id}", null, null, orderId);
        } catch (AlpacaApiException e) {
            if (e.status() != 404 && e.status() != 422) throw e;   // already filled / gone: nothing to cancel
        }
    }

    @Override
    public List<OrderInfo> openOrders() {
        requirePaper();
        List<OrderInfo> out = new ArrayList<>();
        for (JsonNode o : http.get("/v2/orders", Map.of("status", "open", "limit", 500))) out.add(order(o));
        return out;
    }

    private static OrderInfo order(JsonNode o) {
        String fap = o.path("filled_avg_price").asText("");
        return new OrderInfo(o.path("id").asText(), o.path("client_order_id").asText(""), o.path("symbol").asText(),
                o.path("side").asText(""), num(o, "qty"), o.path("status").asText("").toLowerCase(), num(o, "filled_qty"),
                fap.isBlank() || "null".equals(fap) ? null : Double.parseDouble(fap),
                instant(o, "submitted_at"), instant(o, "filled_at"));
    }

    /** Alpaca sends numbers as strings ("12.5") and leaves fields out or null when they do not apply. */
    static double num(JsonNode n, String field) {
        String s = n.path(field).asText("");
        if (s.isBlank() || "null".equals(s)) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    static Instant instant(JsonNode n, String field) {
        String s = n.path(field).asText("");
        if (s.isBlank() || "null".equals(s)) return null;
        try { return OffsetDateTime.parse(s).toInstant(); } catch (Exception e) { return null; }
    }
}
