package com.quant.finance.decision.live;

import com.quant.finance.decision.entity.LiveCycleEntity;
import com.quant.finance.decision.entity.LiveOrderEntity;
import com.quant.finance.decision.error.AlpacaApiException;
import com.quant.finance.decision.live.AlpacaModels.OrderInfo;
import com.quant.finance.decision.live.OrderPlanner.Order;
import com.quant.finance.decision.live.OrderPlanner.Skipped;
import com.quant.finance.decision.live.OrderPlanner.SymbolPlan;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.util.OptionalDouble;

/** Turns planned orders into order rows: sends them to the broker one at a time and waits for the fill, or only records them in a dry run. */
@Slf4j
class OrderExecutor {

    /** Client order ids of this job start with this, so leftovers can be told apart from anyone else's orders. */
    static final String ORDER_PREFIX = "qe-";
    private static final long POLL_MILLIS = 500;
    private static final double QTY_TOLERANCE = 1e-9;

    enum OrderStatus { PENDING, DRY_RUN, SKIPPED, FILLED, PARTIAL, REJECTED, CANCELED, FAILED }

    /** Pauses between polls of an order; replaceable so tests do not wait. */
    interface Sleeper { void sleep(long millis); }

    private final TradingGateway gateway;
    private final LiveStore store;
    private final Clock clock;
    private final Sleeper sleeper;

    OrderExecutor(TradingGateway gateway, LiveStore store, Clock clock, Sleeper sleeper) {
        this.gateway = gateway;
        this.store = store;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    // ---- recording without sending ---------------------------------------------------------------

    void recordDryRun(LiveCycleEntity cycle, SymbolPlan plan, String reason) {
        for (Order order : plan.orders()) {
            LiveOrderEntity row = planRow(cycle, plan, order, reason, true);
            row.setStatus(OrderStatus.DRY_RUN.name());
            row.setSubmittedAt(clock.instant());
            store.saveOrder(row);
        }
    }

    void recordHeldBack(LiveCycleEntity cycle, Skipped skipped, boolean dryRun) {
        LiveOrderEntity row = newRow(cycle, skipped.symbol(), "-", 0, dryRun, "Held back: " + skipped.reason());
        row.setStatus(OrderStatus.SKIPPED.name());
        row.setSubmittedAt(clock.instant());
        store.saveOrder(row);
    }

    // ---- sending -------------------------------------------------------------------------------

    /**
     * Sends the plan's orders in turn, stopping at the first that does not fill completely (a move through zero must not send its second leg after a failed first).
     * Returns the average fill price when every order filled, else empty.
     */
    OptionalDouble send(LiveSettings cfg, LiveCycleEntity cycle, SymbolPlan plan, String reason, CycleStats stats) {
        double filledQty = 0, filledValue = 0;
        int leg = 0;
        for (Order order : plan.orders()) {
            LiveOrderEntity row = planRow(cycle, plan, order, reason, false);
            row.setClientOrderId(ORDER_PREFIX + cycle.getId() + "-" + order.symbol() + "-" + leg++);
            OrderStatus status = place(cfg, order, row);
            store.saveOrder(row);

            if (status != OrderStatus.FILLED) {
                stats.ordersFailed++;
                stats.note(order.symbol() + " " + order.side() + " " + order.qty() + ": " + status.name().toLowerCase()
                        + (row.getError() != null ? " (" + row.getError() + ")" : ""));
                return OptionalDouble.empty();
            }
            stats.ordersFilled++;
            if (row.getFilledAvgPrice() != null) {
                filledQty += row.getFilledQty();
                filledValue += row.getFilledQty() * row.getFilledAvgPrice();
            }
        }
        return filledQty > 0 ? OptionalDouble.of(filledValue / filledQty) : OptionalDouble.empty();
    }

    /** Cancels any order this job left open, e.g. after a crash; others' orders are not touched. */
    void cancelLeftovers() {
        try {
            for (OrderInfo o : gateway.openOrders()) {
                if (o.clientOrderId() == null || !o.clientOrderId().startsWith(ORDER_PREFIX)) continue;
                log.warn("Paper trading: cancelling leftover order {} ({} {} {})", o.id(), o.side(), o.symbol(), o.qty());
                gateway.cancelOrder(o.id());
            }
        } catch (RuntimeException e) {
            log.warn("Paper trading: could not check for leftover orders: {}", e.getMessage());
        }
    }

    /** Submits one order and records how it ended on {@code row}. */
    private OrderStatus place(LiveSettings cfg, Order order, LiveOrderEntity row) {
        try {
            OrderInfo result = submitAndWait(cfg, order, row);
            return record(row, result, order.qty());
        } catch (RuntimeException e) {
            OrderStatus status = e instanceof AlpacaApiException api && api.status() >= 400 && api.status() < 500
                    ? OrderStatus.REJECTED : OrderStatus.FAILED;
            row.setStatus(status.name());
            row.setError(CycleStats.describe(e));
            log.warn("Paper trading: {} {} x{} failed: {}", order.side(), order.symbol(), order.qty(), e.getMessage());
            return status;
        }
    }

    private OrderInfo submitAndWait(LiveSettings cfg, Order order, LiveOrderEntity row) {
        row.setSubmittedAt(clock.instant());
        OrderInfo current = submit(order, row.getClientOrderId());

        Instant deadline = clock.instant().plusSeconds(cfg.fillTimeoutSeconds());
        while (!current.isTerminal() && clock.instant().isBefore(deadline)) {
            sleeper.sleep(POLL_MILLIS);
            current = gateway.order(current.id());
        }
        if (!current.isTerminal()) {
            gateway.cancelOrder(current.id());
            sleeper.sleep(POLL_MILLIS);
            current = gateway.order(current.id());
            row.setError("Not filled within " + cfg.fillTimeoutSeconds() + " s; cancelled");
        }
        return current;
    }

    /** Sends the order; if no answer came back it may still exist, so look for it before calling it failed. */
    private OrderInfo submit(Order order, String clientOrderId) {
        try {
            return gateway.submitMarketOrder(order.symbol(), order.qty(), order.side(), clientOrderId);
        } catch (AlpacaApiException e) {
            if (e.status() != 0) throw e;                                   // an answer: rejected
            return gateway.findByClientOrderId(clientOrderId).orElseThrow(() -> e);
        }
    }

    private OrderStatus record(LiveOrderEntity row, OrderInfo result, long requestedQty) {
        row.setAlpacaOrderId(result.id());
        row.setFilledQty(result.filledQty());
        row.setFilledAvgPrice(result.filledAvgPrice());
        row.setFilledAt(result.filledAt());

        boolean fullyFilled = result.isFilled() && result.filledQty() >= requestedQty - QTY_TOLERANCE;
        OrderStatus status = fullyFilled ? OrderStatus.FILLED
                : result.filledQty() > 0 ? OrderStatus.PARTIAL
                : result.status().equals("rejected") ? OrderStatus.REJECTED
                : OrderStatus.CANCELED;
        row.setStatus(status.name());
        if (!fullyFilled && row.getError() == null)
            row.setError("Order ended " + result.status() + " with " + result.filledQty() + " of " + requestedQty + " filled");
        return status;
    }

    // ---- rows ----------------------------------------------------------------------------------

    private LiveOrderEntity planRow(LiveCycleEntity cycle, SymbolPlan plan, Order order, String reason, boolean dryRun) {
        LiveOrderEntity row = newRow(cycle, order.symbol(), order.side().toUpperCase(), order.qty(), dryRun, reason);
        row.setTargetQty((double) plan.targetQty());
        row.setPositionBefore(plan.positionBefore());
        return row;
    }

    private static LiveOrderEntity newRow(LiveCycleEntity cycle, String symbol, String side, double qty, boolean dryRun, String reason) {
        LiveOrderEntity row = new LiveOrderEntity();
        row.setCycleId(cycle.getId());
        row.setSymbol(symbol);
        row.setSide(side);
        row.setQty(qty);
        row.setStatus(OrderStatus.PENDING.name());
        row.setDryRun(dryRun);
        row.setReason(reason);
        return row;
    }
}
