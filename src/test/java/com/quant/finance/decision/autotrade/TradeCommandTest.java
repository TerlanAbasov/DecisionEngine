package com.quant.finance.decision.autotrade;

import static org.junit.jupiter.api.Assertions.*;

import com.quant.finance.decision.autotrade.AutoTradeSettings.OrderType;
import com.quant.finance.decision.autotrade.AutoTradeSettings.TimeInForce;
import com.quant.finance.decision.autotrade.TradeCommand.Side;
import com.quant.finance.decision.dto.Dtos.SignalDto;
import com.quant.finance.decision.engine.Timeframe;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Locks the JSON this sends against ExecutionEngine's {@code TradeCommandDto}: every one of its fields except {@code chatId}/{@code rawText}
 * (excluded on purpose — Telegram-only, nothing to carry from here), each spelled exactly as that DTO declares it.
 */
class TradeCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant BAR_OPEN = Instant.parse("2026-09-21T14:45:00Z");
    private static final AutoTradeSettings MKT = new AutoTradeSettings(
            true, Instant.parse("2026-09-21T14:00:00Z"), 5, OrderType.MKT, TimeInForce.DAY, List.of(), List.of(), 25);
    private static final AutoTradeSettings LMT = new AutoTradeSettings(
            true, MKT.enabledSince(), 5, OrderType.LMT, TimeInForce.GTC, List.of(), List.of(), 25);

    private static Flip flip(Side side) {
        return new Flip("rsi2", "AAOI", Timeframe.M15, side, BAR_OPEN, 99.0, 103.5, 98.25, 101.5);
    }

    private static JsonNode tree(TradeCommand command) throws Exception {
        return JSON.readTree(JSON.writeValueAsString(command));
    }

    /** Every field TradeCommandDto declares besides chatId/rawText, spelled exactly as it does. */
    private static final Set<String> DTO_FIELDS = Set.of(
            "command",
            "identifier",
            "strategy",
            "action",
            "quantity",
            "orderType",
            "limitPrice",
            "tif",
            "open",
            "high",
            "low",
            "close");

    @Test
    void aMarketOrderFromAFlipSendsExactlyTheFieldNamesTradeCommandDtoDeclaresNoMoreNoLess() throws Exception {
        JsonNode node = tree(TradeCommand.of(flip(Side.BUY), MKT));
        Set<String> present = new HashSet<>(node.propertyNames());

        assertTrue(DTO_FIELDS.containsAll(present), "sent a field TradeCommandDto does not have: " + present);
        assertFalse(present.contains("chatId"), "chatId is excluded on purpose");
        assertFalse(present.contains("rawText"), "rawText is excluded on purpose");
        assertFalse(present.contains("limitPrice"), "a market order carries no limit price");
    }

    @Test
    void aBuyFlipMapsToBuyOnBothCommandAndActionTheSymbolAsIdentifierAndTheBarsOwnPrices() throws Exception {
        JsonNode node = tree(TradeCommand.of(flip(Side.BUY), MKT));

        assertEquals("BUY", node.get("command").asString());
        assertEquals("BUY", node.get("action").asString());
        assertEquals("AAOI", node.get("identifier").asString());
        assertEquals("rsi2", node.get("strategy").asString());
        assertEquals(5.0, node.get("quantity").asDouble());
        assertEquals("MKT", node.get("orderType").asString());
        assertEquals("DAY", node.get("tif").asString());
        assertEquals(99.0, node.get("open").asDouble());
        assertEquals(103.5, node.get("high").asDouble());
        assertEquals(98.25, node.get("low").asDouble());
        assertEquals(101.5, node.get("close").asDouble());
    }

    @Test
    void aSellFlipMapsToSellOnBothCommandAndAction() throws Exception {
        JsonNode node = tree(TradeCommand.of(flip(Side.SELL), MKT));
        assertEquals("SELL", node.get("command").asString());
        assertEquals("SELL", node.get("action").asString());
    }

    @Test
    void aLimitOrderCarriesTheSignalBarsCloseAsItsLimitPrice() throws Exception {
        JsonNode node = tree(TradeCommand.of(flip(Side.BUY), LMT));
        assertEquals("LMT", node.get("orderType").asString());
        assertEquals("GTC", node.get("tif").asString());
        assertEquals(101.5, node.get("limitPrice").asDouble());
        assertEquals(101.5, node.get("close").asDouble(), "the limit price and the bar close must agree");
    }

    @Test
    void aHandForwardedSignalHasOnlyTheCloseNoOpenHighOrLowSinceThatIsAllThatIsKnown() throws Exception {
        SignalDto signal = new SignalDto("macd", "trend", "MSFT", "LONG", true, 1, 1.0, 91.5, BAR_OPEN, "M15");
        JsonNode node = tree(TradeCommand.of(signal, MKT));

        assertEquals("BUY", node.get("command").asString());
        assertEquals("MSFT", node.get("identifier").asString());
        assertEquals(91.5, node.get("close").asDouble());
        assertFalse(
                node.has("open"),
                "no bar was read for a hand-forwarded signal, so open must be omitted, not fabricated");
        assertFalse(node.has("high"));
        assertFalse(node.has("low"));
    }

    @Test
    void aHandForwardedShortSignalMapsToSell() throws Exception {
        SignalDto signal = new SignalDto("macd", "trend", "MSFT", "SHORT", true, 1, -1.0, 91.5, BAR_OPEN, "M15");
        assertEquals("SELL", tree(TradeCommand.of(signal, MKT)).get("command").asString());
    }

    @Test
    void aFlatHandForwardedSignalIsRejectedSinceThereIsNoCloseCommand() {
        SignalDto flat = new SignalDto("macd", "trend", "MSFT", "FLAT", false, 1, 0.0, 91.5, BAR_OPEN, "M15");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TradeCommand.of(flat, MKT));
        assertTrue(error.getMessage().contains("FLAT"), error.getMessage());
    }
}
