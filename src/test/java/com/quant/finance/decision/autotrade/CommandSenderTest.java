package com.quant.finance.decision.autotrade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.finance.decision.autotrade.AutoTradeSettings.OrderType;
import com.quant.finance.decision.autotrade.AutoTradeSettings.TimeInForce;
import com.quant.finance.decision.autotrade.CommandSender.SendResult;
import com.quant.finance.decision.autotrade.TradeCommand.Side;
import com.quant.finance.decision.engine.Timeframe;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** The real Feign client against a stand-in ExecutionEngine, so the payload and how each kind of reply is judged are checked as they run. */
class CommandSenderTest {

    @Configuration
    @EnableFeignClients(clients = {ExecutionEngineApi.class})
    @ImportAutoConfiguration({HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class, FeignAutoConfiguration.class})
    static class Config {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String reply = "📊 Symbol will be bought";
    private HttpServer server;
    private AnnotationConfigApplicationContext ctx;

    private static final Flip FLIP = new Flip("rsi_reversion", "AAPL", Timeframe.M15, Side.BUY, Instant.parse("2026-09-21T14:45:00Z"), 101.5);
    private static final AutoTradeSettings SETTINGS = new AutoTradeSettings(true, Instant.parse("2026-09-21T14:00:00Z"), 5, OrderType.MKT,
            TimeInForce.DAY, List.of(), List.of(), 25);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + exchange.getRequestHeaders().getFirst("Content-Type"));
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain;charset=UTF-8");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        if (ctx != null) ctx.close();
    }

    private CommandSender senderFor(String url) {
        ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("decision.execution-engine.url", url)));
        ctx.register(Config.class);
        ctx.refresh();
        return new CommandSender(ctx.getBean(ExecutionEngineApi.class), url);
    }

    private CommandSender sender() { return senderFor("http://localhost:" + server.getAddress().getPort()); }

    @Test
    void sendsTheCommandAsJsonToTheTradeControllerAndReportsAnAcceptedReply() throws Exception {
        SendResult result = sender().send(TradeCommand.of(FLIP, SETTINGS));

        assertEquals(CommandStatus.SENT, result.status());
        assertEquals("📊 Symbol will be bought", result.message());
        assertEquals(1, requests.size());
        assertTrue(paths.get(0).startsWith("POST /api/v1/trades/command application/json"), paths.get(0));
        JsonNode body = JSON.readTree(requests.get(0));
        assertEquals("BUY", body.path("command").asText());
        assertEquals("BUY", body.path("action").asText());
        assertEquals("AAPL", body.path("identifier").asText());
        assertEquals("rsi_reversion", body.path("strategy").asText());
        assertEquals(5.0, body.path("quantity").asDouble());
        assertEquals("MKT", body.path("orderType").asText());
        assertEquals("DAY", body.path("tif").asText());
        assertTrue(body.path("rawText").asText().contains("rsi_reversion AAPL tf=M15"), body.toString());
        assertFalse(body.has("limitPrice"), "a market order carries no limit price: " + body);
    }

    @Test
    void aLimitOrderCarriesTheBarCloseAsItsLimitAndASellIsASell() throws Exception {
        Flip sell = new Flip("macd", "MSFT", Timeframe.H1, Side.SELL, Instant.parse("2026-09-21T14:00:00Z"), 420.25);
        AutoTradeSettings limit = new AutoTradeSettings(true, SETTINGS.enabledSince(), 2.5, OrderType.LMT, TimeInForce.GTC, List.of(), List.of(), 25);
        sender().send(TradeCommand.of(sell, limit));

        JsonNode body = JSON.readTree(requests.get(0));
        assertEquals("SELL", body.path("command").asText());
        assertEquals("SELL", body.path("action").asText());
        assertEquals(420.25, body.path("limitPrice").asDouble());
        assertEquals("LMT", body.path("orderType").asText());
        assertEquals("GTC", body.path("tif").asText());
        assertEquals(2.5, body.path("quantity").asDouble());
    }

    @Test
    void aFailureReportedInTheTextOfA200ReplyIsARejectionNotASuccess() {
        reply = "❌ Error while executing command: no such contract";
        SendResult result = sender().send(TradeCommand.of(FLIP, SETTINGS));
        assertEquals(CommandStatus.REJECTED, result.status());
        assertTrue(result.message().contains("no such contract"));

        reply = "Unknown command : UNKNOWN";
        assertEquals(CommandStatus.REJECTED, sender().send(TradeCommand.of(FLIP, SETTINGS)).status());
    }

    @Test
    void httpErrorsAreRejectionsCarryingTheStatusAndBody() {
        status = 500;
        reply = "boom";
        SendResult result = sender().send(TradeCommand.of(FLIP, SETTINGS));
        assertEquals(CommandStatus.REJECTED, result.status());
        assertTrue(result.message().startsWith("HTTP 500"), result.message());
        assertTrue(result.message().contains("boom"), result.message());

        status = 400;
        assertEquals(CommandStatus.REJECTED, sender().send(TradeCommand.of(FLIP, SETTINGS)).status());
    }

    @Test
    void whenNothingAnswersTheOutcomeIsUnknownAndItIsSaidSoAndNeverRetried() throws IOException {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) { freePort = s.getLocalPort(); }
        SendResult result = senderFor("http://localhost:" + freePort).send(TradeCommand.of(FLIP, SETTINGS));
        assertEquals(CommandStatus.FAILED, result.status());
        assertTrue(result.message().contains("may or may not have arrived"), result.message());
    }

    @Test
    void aRepliedCommandIsSentExactlyOnceEvenWhenItFails() {
        status = 503;
        sender().send(TradeCommand.of(FLIP, SETTINGS));
        assertEquals(1, requests.size(), "Feign must not retry an order");
    }

    @Test
    void withoutAUrlNothingIsSent() {
        ExecutionEngineApi api = mock(ExecutionEngineApi.class);
        CommandSender blank = new CommandSender(api, "  ");
        assertFalse(blank.isConfigured());
        assertEquals(CommandStatus.FAILED, blank.send(TradeCommand.of(FLIP, SETTINGS)).status());
        assertThrows(IllegalStateException.class, () -> blank.sendOrThrow(TradeCommand.of(FLIP, SETTINGS)));
        assertFalse(new CommandSender(api, null).isConfigured());
        verifyNoInteractions(api);
    }

    @Test
    void theClientStartsWhenNoUrlIsConfiguredSoAnUnconfiguredAppStillBoots() {
        CommandSender sender = senderFor("");               // a blank url property, as in the prod default
        assertFalse(sender.isConfigured());
        assertEquals(CommandStatus.FAILED, sender.send(TradeCommand.of(FLIP, SETTINGS)).status());
        assertTrue(requests.isEmpty());
    }

    @Test
    void anUnusableUrlCountsAsNotConfiguredInsteadOfCrashingTheApp() {
        ExecutionEngineApi api = mock(ExecutionEngineApi.class);
        for (String bad : new String[]{"not a url", "localhost:8081", "ftp://host/x", "http://", "http://bad host"})
            assertFalse(new CommandSender(api, bad).isConfigured(), bad);
        assertTrue(new CommandSender(api, " https://engine.example.com:8081 ").isConfigured());
        verifyNoInteractions(api);
    }

    @Test
    void sendOrThrowReturnsTheReplyOrThrowsWhatWentWrong() {
        CommandSender sender = sender();
        assertEquals("📊 Symbol will be bought", sender.sendOrThrow(TradeCommand.of(FLIP, SETTINGS)));
        reply = "❌ Error while executing command: closed";
        ExecutionEngineException e = assertThrows(ExecutionEngineException.class, () -> sender.sendOrThrow(TradeCommand.of(FLIP, SETTINGS)));
        assertTrue(e.getMessage().contains("closed"));
    }
}
