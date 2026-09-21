package com.quant.finance.decision.live;

import com.quant.finance.decision.error.AlpacaApiException;
import com.quant.finance.decision.client.AlpacaClient;
import com.quant.finance.decision.client.AlpacaFeign;
import com.fasterxml.jackson.databind.JsonNode;
import com.quant.finance.decision.live.AlpacaModels.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The paper-trading gateway over {@link AlpacaClient}: every call first checks the configured endpoint is Alpaca's paper host (or localhost, for tests)
 * and refuses otherwise, so a wrong base URL can never send an order to a live account; the client's interceptor enforces the same rule.
 */
@Component
@Slf4j
public class AlpacaTradingClient implements TradingGateway {

    private final AlpacaClient client;
    private final String baseUrl;

    @Autowired
    public AlpacaTradingClient(AlpacaClient client,
                               @Value("${decision.alpaca.trading-base-url:https://paper-api.alpaca.markets}") String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    @Override
    public boolean isPaper() {
        return AlpacaFeign.isPaperUrl(baseUrl);
    }

    private void requirePaper() {
        if (!isPaper())
            throw new IllegalStateException("Refusing to trade: decision.alpaca.trading-base-url is '" + baseUrl
                    + "', which is not the paper-trading endpoint (https://" + AlpacaFeign.PAPER_HOST + "). "
                    + "This job only ever trades a paper (demo) account.");
    }

    @Override
    public AccountInfo account() {
        requirePaper();
        JsonNode n = AlpacaCalls.read(client::account);
        return new AccountInfo(n.path("status").asText(""), num(n, "equity"), num(n, "cash"), num(n, "buying_power"),
                num(n, "long_market_value"), num(n, "short_market_value"),
                n.path("trading_blocked").asBoolean(false), n.path("account_blocked").asBoolean(false),
                n.path("shorting_enabled").asBoolean(false));
    }

    @Override
    public MarketClock clock() {
        requirePaper();
        JsonNode n = AlpacaCalls.read(client::clock);
        return new MarketClock(n.path("is_open").asBoolean(false), instant(n, "timestamp"),
                instant(n, "next_open"), instant(n, "next_close"));
    }

    @Override
    public List<PositionInfo> positions() {
        requirePaper();
        List<PositionInfo> out = new ArrayList<>();
        for (JsonNode p : AlpacaCalls.read(client::positions))
            out.add(new PositionInfo(p.path("symbol").asText(), num(p, "qty"), num(p, "avg_entry_price"),
                    num(p, "current_price"), num(p, "market_value"), num(p, "unrealized_pl")));
        return out;
    }

    @Override
    public AssetInfo asset(String symbol) {
        requirePaper();
        JsonNode n = AlpacaCalls.read(() -> client.asset(symbol));
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
        return order(AlpacaCalls.once(() -> client.submitOrder(body)));
    }

    @Override
    public Optional<OrderInfo> findByClientOrderId(String clientOrderId) {
        requirePaper();
        try {
            return Optional.of(order(AlpacaCalls.read(() -> client.orderByClientId(clientOrderId))));
        } catch (AlpacaApiException e) {
            if (e.status() == 404) return Optional.empty();
            throw e;
        }
    }

    @Override
    public OrderInfo order(String orderId) {
        requirePaper();
        return order(AlpacaCalls.read(() -> client.order(orderId)));
    }

    @Override
    public void cancelOrder(String orderId) {
        requirePaper();
        try {
            AlpacaCalls.once(() -> client.cancelOrder(orderId));
        } catch (AlpacaApiException e) {
            if (e.status() != 404 && e.status() != 422) throw e;   // already filled / gone: nothing to cancel
        }
    }

    @Override
    public List<OrderInfo> openOrders() {
        requirePaper();
        List<OrderInfo> out = new ArrayList<>();
        for (JsonNode o : AlpacaCalls.read(() -> client.orders("open", 500))) out.add(order(o));
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
