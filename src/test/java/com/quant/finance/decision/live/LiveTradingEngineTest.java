package com.quant.finance.decision.live;

import com.quant.finance.decision.domain.*;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.live.AlpacaModels.*;
import com.quant.finance.decision.live.LiveFakes.*;
import com.quant.finance.decision.live.LiveTradingEngine.Mode;
import com.quant.finance.decision.live.LiveTradingEngine.Trigger;
import com.quant.finance.decision.service.StrategyService;
import com.quant.finance.decision.service.UniverseService;
import com.quant.finance.decision.strategy.TradingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveTradingEngineTest {

    private static final Instant NOW = Instant.parse("2026-09-21T15:00:00Z");

    private final Map<String, Double> prices = new HashMap<>();
    private final Map<String, Double> signal = new HashMap<>();
    private final Map<String, Double> stopLoss = new HashMap<>();
    private MutableClock clock;
    private FakeGateway gateway;
    private FakeData data;
    private MemoryStore store;
    private StrategyService strategies;
    private LiveTradingEngine engine;

    @BeforeEach
    void setUp() {
        prices.clear(); signal.clear(); stopLoss.clear();
        prices.put("A", 100.0); prices.put("B", 100.0); prices.put("C", 100.0);
        clock = new MutableClock(NOW);
        gateway = new FakeGateway(prices);
        data = new FakeData(prices, NOW);
        store = new MemoryStore();
        strategies = mock(StrategyService.class);
        when(strategies.getParams(anyString())).thenReturn(Map.of());
        when(strategies.isStrategy(anyString())).thenReturn(true);
        when(strategies.recommendedTimeframe(anyString())).thenReturn(Timeframe.M15);
        when(strategies.defaultStopLossPct(anyString())).thenAnswer(i -> stopLoss.get(i.<String>getArgument(0)));
        when(strategies.defaultTakeProfitPct(anyString())).thenReturn(null);
        when(strategies.getStrategies(any())).thenAnswer(i -> {
            Map<String, TradingStrategy> m = new LinkedHashMap<>();
            for (String n : i.<Collection<String>>getArgument(0)) m.put(n, LiveFakes.constant(n, signal));
            return m;
        });
        when(strategies.getEnabledStrategies()).thenAnswer(i -> {
            Map<String, TradingStrategy> m = new LinkedHashMap<>();
            for (String n : new TreeSet<>(signal.keySet())) m.put(n, LiveFakes.constant(n, signal));
            return m;
        });
        UniverseService universe = mock(UniverseService.class);
        when(universe.get()).thenReturn(List.of("A", "B", "C"));
        engine = new LiveTradingEngine(gateway, data, strategies, universe, store, clock, Runnable::run,
                ms -> clock.advance(ms));
    }

    private LiveSettings cfg(String... symbols) { return cfgOf(List.of("s1", "s2"), symbols); }

    private LiveSettings cfgOf(List<String> names, String... symbols) {
        return new LiveSettings(true, false, 300, 1000, 1.0, true, "M15", 300, names, List.of(symbols),
                1_000_000, 50, true, 30, false);
    }

    private LiveCycleEntity run(LiveSettings c) { return engine.runCycle(c, Trigger.MANUAL, Mode.NORMAL); }

    private LiveSlotEntity slot(String strategy, String symbol) { return store.slots.get(LiveStore.key(strategy, symbol)); }

    // ---------------------------------------------------------------------------------------------

    @Test
    void aDryRunPlansOrdersButSendsNothingAndChangesNoPositions() {
        signal.put("s1", 1.0); signal.put("s2", 1.0);
        gateway.leftovers.add(new OrderInfo("x", "qe-1-A-0", "A", "buy", 1, "new", 0, null, null, null));
        LiveSettings dry = new LiveSettings(true, true, 300, 1000, 1.0, true, "M15", 300, List.of("s1", "s2"),
                List.of("A"), 1_000_000, 50, true, 30, false);
        LiveCycleEntity c = run(dry);
        assertEquals("COMPLETED", c.getStatus());
        assertTrue(c.getMessage().contains("dry run"), c.getMessage());
        assertTrue(gateway.submitted.isEmpty(), "no order may reach the broker");
        assertTrue(gateway.cancelled.isEmpty(), "a dry run does not even cancel leftovers");
        assertTrue(store.slots.isEmpty() && store.trades.isEmpty(), "the ledger is untouched");
        assertEquals(1, store.ordersWith("DRY_RUN").size());
        LiveOrderEntity o = store.ordersWith("DRY_RUN").get(0);
        assertEquals("BUY", o.getSide());
        assertEquals(20, o.getQty(), 1e-9);
        assertTrue(o.isDryRun());
        assertEquals(1, store.equity.size());
        assertEquals(1, c.getOrdersPlanned());
    }

    @Test
    void theFirstLiveCycleBuysTheNetOfAllStrategiesAndOpensTheirSlots() {
        signal.put("s1", 1.0); signal.put("s2", 1.0);
        LiveCycleEntity c = run(cfg("A"));
        assertEquals("COMPLETED", c.getStatus());
        assertEquals(1, gateway.submitted.size());
        assertEquals(20, gateway.submitted.get(0).qty(), 1e-9);
        assertEquals("buy", gateway.submitted.get(0).side());
        assertEquals(20.0, gateway.positions.get("A"));
        for (String s : List.of("s1", "s2")) {
            LiveSlotEntity sl = slot(s, "A");
            assertEquals(1, sl.getDirection());
            assertEquals(10, sl.getQty(), 1e-9);
            assertEquals(100, sl.getEntryPrice(), 1e-9);
            assertEquals(1.0, sl.getLastSignal());
        }
        assertEquals(1, c.getOrdersFilled());
        assertEquals("FILLED", store.orders.get(0).getStatus());
        assertEquals(2, store.pnl.size(), "one curve point per strategy");
        assertEquals(1, store.equity.size());
    }

    @Test
    void aSecondCycleWithUnchangedSignalsSendsNothing() {
        signal.put("s1", 1.0); signal.put("s2", 1.0);
        run(cfg("A"));
        LiveCycleEntity c = run(cfg("A"));
        assertEquals(1, gateway.submitted.size(), "no new order");
        assertEquals(0, c.getOrdersPlanned());
        assertEquals(0, c.getTradesClosed());
    }

    @Test
    void closingOneStrategySellsOnlyItsShareAndBooksItsPnlAtTheFillPrice() {
        signal.put("s1", 1.0); signal.put("s2", 1.0);
        run(cfg("A"));
        prices.put("A", 110.0);
        signal.put("s1", 0.0);
        LiveCycleEntity c = run(cfg("A"));
        assertEquals(2, gateway.submitted.size());
        assertEquals("sell", gateway.submitted.get(1).side());
        assertEquals(10, gateway.submitted.get(1).qty(), 1e-9);
        assertEquals(10.0, gateway.positions.get("A"));
        assertEquals(1, store.trades.size());
        LiveTradeEntity t = store.trades.get(0);
        assertEquals("s1", t.getStrategy());
        assertEquals("LONG", t.getSide());
        assertEquals(100, t.getPnlUsd(), 1e-9);
        assertEquals(10, t.getReturnPct(), 1e-9);
        assertEquals("SIGNAL", t.getExitReason());
        assertEquals(110, t.getExitPrice(), 1e-9);
        assertEquals(100, slot("s1", "A").getRealizedPnl(), 1e-9);
        assertEquals(0, slot("s1", "A").getDirection());
        assertEquals(1, slot("s2", "A").getDirection());
        assertEquals(110, slot("s2", "A").getLastPrice(), 1e-9, "the open slot is marked to market");
        assertEquals(1, c.getTradesClosed());
    }

    @Test
    void strategiesThatOffsetEachOtherNeedNoOrderButBothPositionsAreRecorded() {
        signal.put("s1", 1.0); signal.put("s2", -1.0);
        LiveCycleEntity c = run(cfg("A"));
        assertTrue(gateway.submitted.isEmpty());
        assertEquals(1, slot("s1", "A").getDirection());
        assertEquals(-1, slot("s2", "A").getDirection());
        assertEquals(0, c.getOrdersPlanned());
        assertEquals("COMPLETED", c.getStatus());
    }

    @Test
    void aRejectedOrderLeavesTheLedgerUntouchedAndTheNextCycleRetries() {
        signal.put("s1", 1.0);
        gateway.reject.add("A");
        LiveCycleEntity c = run(cfgOf(List.of("s1"), "A"));
        assertEquals("COMPLETED", c.getStatus());
        assertEquals(1, c.getOrdersFailed());
        assertEquals(1, store.ordersWith("REJECTED").size());
        assertEquals("insufficient buying power", store.ordersWith("REJECTED").get(0).getError());
        assertTrue(c.getMessage().contains("insufficient buying power"), c.getMessage());
        assertTrue(store.slots.isEmpty(), "no position was taken, so none is recorded");

        gateway.reject.clear();
        LiveCycleEntity again = run(cfgOf(List.of("s1"), "A"));
        assertEquals(1, again.getOrdersFilled());
        assertEquals(1, slot("s1", "A").getDirection());
    }

    @Test
    void whenTheMarketIsClosedTheCycleIsSkippedAndTradesNothing() {
        signal.put("s1", 1.0);
        gateway.marketOpen = false;
        LiveCycleEntity c = run(cfgOf(List.of("s1"), "A"));
        assertEquals("SKIPPED", c.getStatus());
        assertTrue(c.getMessage().startsWith("Market closed"), c.getMessage());
        assertTrue(gateway.submitted.isEmpty());
        assertEquals(1, store.equity.size(), "the account is still recorded");

        LiveSettings anyTime = new LiveSettings(true, false, 300, 1000, 1.0, true, "M15", 300, List.of("s1"),
                List.of("A"), 1_000_000, 50, false, 30, false);
        assertEquals("COMPLETED", run(anyTime).getStatus());
        assertEquals(1, gateway.submitted.size());
    }

    @Test
    void itRefusesAnythingButAPaperEndpointAndABlockedAccount() {
        signal.put("s1", 1.0);
        gateway.paper = false;
        LiveCycleEntity c = run(cfgOf(List.of("s1"), "A"));
        assertEquals("FAILED", c.getStatus());
        assertTrue(c.getMessage().contains("Refusing"), c.getMessage());
        assertTrue(gateway.submitted.isEmpty());

        gateway.paper = true;
        gateway.account = new AccountInfo("ACCOUNT_UPDATED", 1, 1, 1, 0, 0, true, false, true);
        LiveCycleEntity b = run(cfgOf(List.of("s1"), "A"));
        assertEquals("FAILED", b.getStatus());
        assertTrue(b.getMessage().contains("blocked"), b.getMessage());
        assertTrue(gateway.submitted.isEmpty());
    }

    @Test
    void anOrderThatDoesNotFillInTimeIsCancelledAndNotRecorded() {
        signal.put("s1", 1.0);
        gateway.neverFill = true;
        LiveCycleEntity c = run(cfgOf(List.of("s1"), "A"));
        assertEquals(1, gateway.cancelled.size());
        assertEquals(1, c.getOrdersFailed());
        LiveOrderEntity o = store.ordersWith("CANCELED").get(0);
        assertTrue(o.getError().contains("Not filled within 30 s"), o.getError());
        assertTrue(store.slots.isEmpty());
    }

    @Test
    void aPartialFillIsNotBookedAndTheNextCycleOrdersTheRemainder() {
        signal.put("s1", 1.0);
        gateway.fillFraction = 0.5;
        LiveCycleEntity c = run(cfgOf(List.of("s1"), "A"));          // wants 10, gets 5
        assertEquals(1, store.ordersWith("PARTIAL").size());
        assertEquals(5.0, gateway.positions.get("A"));
        assertTrue(store.slots.isEmpty());
        assertEquals(1, c.getOrdersFailed());

        gateway.fillFraction = 1.0;
        LiveCycleEntity next = run(cfgOf(List.of("s1"), "A"));
        assertEquals(5, gateway.submitted.get(1).qty(), 1e-9, "only the missing 5 shares");
        assertEquals(10.0, gateway.positions.get("A"));
        assertEquals(1, slot("s1", "A").getDirection());
        assertEquals(1, next.getOrdersFilled());
    }

    @Test
    void aStrategyLeftOutOfTheSettingsIsClosedAsRemoved() {
        signal.put("s1", 1.0); signal.put("s2", 1.0);
        run(cfg("A"));
        run(cfgOf(List.of("s1"), "A"));
        assertEquals(0, slot("s2", "A").getDirection());
        assertEquals("REMOVED", store.trades.get(0).getExitReason());
        assertEquals(10.0, gateway.positions.get("A"));
    }

    @Test
    void flattenClosesEverythingRegardlessOfSignals() {
        signal.put("s1", 1.0); signal.put("s2", 1.0);
        run(cfg("A", "B"));
        assertEquals(40.0, gateway.positions.get("A") + gateway.positions.get("B"));
        LiveCycleEntity c = engine.runCycle(cfg("A", "B"), Trigger.MANUAL, Mode.FLATTEN);
        assertEquals("COMPLETED", c.getStatus());
        assertEquals(0.0, gateway.positions.get("A"), 1e-9);
        assertEquals(0.0, gateway.positions.get("B"), 1e-9);
        assertEquals(4, store.trades.size());
        assertTrue(store.trades.stream().allMatch(t -> t.getExitReason().equals("FLATTEN")));
        assertTrue(store.slots.values().stream().allMatch(s -> s.getDirection() == 0));
    }

    @Test
    void theExposureCapHoldsBackOrdersAndTheirLedgerChanges() {
        signal.put("s1", 1.0);
        LiveSettings capped = new LiveSettings(true, false, 300, 1000, 1.0, true, "M15", 300, List.of("s1"),
                List.of("A", "B"), 1_500, 50, true, 30, false);
        LiveCycleEntity c = run(capped);
        assertEquals(1, gateway.submitted.size(), "$2000 wanted, $1500 allowed: one of the two");
        assertEquals(1, store.slots.size());
        assertTrue(c.getMessage().contains("held back"), c.getMessage());
        assertEquals(1, store.ordersWith("SKIPPED").size());
    }

    @Test
    void theOrderLimitHoldsBackTheRest() {
        signal.put("s1", 1.0);
        LiveSettings limited = new LiveSettings(true, false, 300, 1000, 1.0, true, "M15", 300, List.of("s1"),
                List.of("A", "B", "C"), 1_000_000, 2, true, 30, false);
        run(limited);
        assertEquals(2, gateway.submitted.size());
        assertEquals(2, store.slots.size());
    }

    @Test
    void aStopLossClosesThePositionAndStaysOutUntilTheSignalChanges() {
        signal.put("s1", 1.0);
        stopLoss.put("s1", 5.0);
        LiveSettings risk = new LiveSettings(true, false, 300, 1000, 1.0, true, "M15", 300, List.of("s1"),
                List.of("A"), 1_000_000, 50, true, 30, true);
        run(risk);
        prices.put("A", 94.0);
        run(risk);
        assertEquals("STOP_LOSS", store.trades.get(0).getExitReason());
        assertEquals(-60, store.trades.get(0).getPnlUsd(), 1e-9);
        assertEquals(0.0, gateway.positions.get("A"), 1e-9);
        int orders = gateway.submitted.size();
        prices.put("A", 100.0);
        run(risk);
        assertEquals(orders, gateway.submitted.size(), "still asking for the stopped-out direction: no re-entry");
        signal.put("s1", 0.0);
        run(risk);
        signal.put("s1", 1.0);
        run(risk);
        assertEquals(orders + 1, gateway.submitted.size(), "re-armed after the signal moved on");
    }

    @Test
    void goingFromLongToShortIsTwoOrders() {
        signal.put("s1", 1.0);
        run(cfgOf(List.of("s1"), "A"));
        signal.put("s1", -1.0);
        LiveCycleEntity c = run(cfgOf(List.of("s1"), "A"));
        assertEquals(3, gateway.submitted.size());
        assertEquals(-10.0, gateway.positions.get("A"), 1e-9);
        assertEquals(2, c.getOrdersFilled());
        assertEquals(-1, slot("s1", "A").getDirection());
        assertEquals(1, store.trades.size());
    }

    @Test
    void theSecondLegOfAMoveThroughZeroIsNotSentWhenTheFirstFails() {
        signal.put("s1", 1.0);
        run(cfgOf(List.of("s1"), "A"));
        signal.put("s1", -1.0);
        gateway.reject.add("A");
        LiveCycleEntity c = run(cfgOf(List.of("s1"), "A"));
        assertEquals(1, gateway.submitted.size(), "nothing new was sent");
        assertEquals(1, c.getOrdersFailed());
        assertEquals(1, slot("s1", "A").getDirection(), "the ledger still matches the account");
    }

    @Test
    void aSymbolWithoutAPriceOrThatIsNotTradableIsSkippedWithANote() {
        signal.put("s1", 1.0);
        prices.remove("A");
        gateway.assets.put("B", new AssetInfo("B", false, false, false, false, "inactive"));
        LiveCycleEntity c = run(cfgOf(List.of("s1"), "A", "B", "C"));
        assertTrue(c.getMessage().contains("no price for A"), c.getMessage());
        assertTrue(c.getMessage().contains("B is not tradable"), c.getMessage());
        assertEquals(1, gateway.submitted.size());
        assertEquals("C", gateway.submitted.get(0).symbol());
        assertEquals(2, c.getErrors());
    }

    @Test
    void shortsAreNotTakenInASymbolThatCannotBeShorted() {
        signal.put("s1", -1.0);
        gateway.assets.put("A", new AssetInfo("A", true, false, false, true, "active"));
        run(cfgOf(List.of("s1"), "A", "B"));
        assertEquals(1, gateway.submitted.size(), "only B is shorted");
        assertEquals("B", gateway.submitted.get(0).symbol());
        assertEquals("sell", gateway.submitted.get(0).side());
        assertEquals(0, slot("s1", "A").getDirection());
    }

    @Test
    void oneSymbolsDataFailureAndOneBrokenStrategyDoNotStopTheRest() {
        signal.put("s1", 1.0);                               // s2 has no signal entry -> it throws
        data.failBars.add("A");
        LiveCycleEntity c = run(cfg("A", "B"));
        assertEquals("COMPLETED", c.getStatus());
        assertTrue(c.getErrors() >= 2, "the bars failure and the broken strategy are counted: " + c.getErrors());
        assertEquals(1, gateway.submitted.size());
        assertEquals("B", gateway.submitted.get(0).symbol());
        assertEquals(10, gateway.submitted.get(0).qty(), 1e-9);
    }

    @Test
    void leftoverOrdersOfThisJobAreCancelledButNobodyElsesAre() {
        signal.put("s1", 0.0);
        gateway.leftovers.add(new OrderInfo("mine", "qe-9-A-0", "A", "buy", 1, "new", 0, null, null, null));
        gateway.leftovers.add(new OrderInfo("theirs", "manual-1", "A", "buy", 1, "new", 0, null, null, null));
        run(cfgOf(List.of("s1"), "A"));
        assertEquals(List.of("mine"), gateway.cancelled);
    }

    @Test
    void aFailedCommitAfterAFillHealsOnTheNextCycleWithoutOrderingAgain() {
        signal.put("s1", 1.0);
        store.failCommit = true;
        LiveCycleEntity c = run(cfgOf(List.of("s1"), "A"));
        assertTrue(c.getErrors() >= 1 && c.getMessage().contains("could not save"), c.getMessage());
        assertEquals(10.0, gateway.positions.get("A"));
        assertTrue(store.slots.isEmpty());

        store.failCommit = false;
        LiveCycleEntity next = run(cfgOf(List.of("s1"), "A"));
        assertEquals(1, gateway.submitted.size(), "the account already holds the position: no second order");
        assertEquals(0, next.getOrdersPlanned());
        assertEquals(1, slot("s1", "A").getDirection());
    }

    @Test
    void theCycleRecordsWhatItDid() {
        signal.put("s1", 1.0); signal.put("s2", 0.0);
        LiveCycleEntity c = run(cfg("A", "B"));
        assertEquals(2, c.getSymbols());
        assertEquals(2, c.getStrategies());
        assertEquals(4, c.getSignals());
        assertEquals(2, c.getOrdersPlanned());
        assertEquals(2, c.getOrdersFilled());
        assertEquals("MANUAL", c.getTriggeredBy());
        assertEquals("NORMAL", c.getMode());
        assertNotNull(c.getFinishedAt());
        assertNotNull(c.getDurationMs());
    }
}
