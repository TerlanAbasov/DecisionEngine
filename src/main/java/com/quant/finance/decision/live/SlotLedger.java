package com.quant.finance.decision.live;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The virtual position of one strategy in one symbol and how it reacts to a new signal. Alpaca keeps a single
 * net position per symbol, so the job keeps each strategy's own position here (in shares, fractional) and sends
 * only the net to the broker. This is what lets a strategy's real-time return be measured separately.
 *
 * <p>Pure functions, no I/O. The rules mirror the backtester: a signal is a target position in [-1, 1]; a
 * stop-loss / take-profit closes the position and keeps it closed until the strategy stops asking for that same
 * direction; shorts can be switched off.
 */
public final class SlotLedger {
    private SlotLedger() {}

    /** A held position changes size only when the wanted size differs by more than this share (avoids churn on tiny drifts). */
    static final double RESIZE_TOLERANCE = 0.25;
    private static final double EPS = 1e-9;

    public static final String SIGNAL = "SIGNAL", STOP_LOSS = "STOP_LOSS", TAKE_PROFIT = "TAKE_PROFIT",
            RESIZE = "RESIZE", REMOVED = "REMOVED", FLATTEN = "FLATTEN";

    /** direction -1 / 0 / 1; {@code qty} is the magnitude in shares (0 when flat). */
    public record State(int direction, double qty, double entryPrice, Instant entryTime, int blockedDir,
                        double realizedPnl) {
        public static final State FLAT = new State(0, 0, 0, null, 0, 0);

        /** Signed shares held. */
        public double signedQty() { return direction * qty; }
    }

    public record Params(double allocationUsd, double positionSize, boolean allowShort,
                         double stopLossPct, double takeProfitPct) {}

    public record ClosedTrade(String side, double qty, Instant entryTime, double entryPrice,
                              Instant exitTime, double exitPrice, double pnlUsd, double returnPct, String reason) {}

    /** {@code qtyDelta} is the change in signed virtual shares this transition makes. */
    public record Transition(State next, List<ClosedTrade> closed, double qtyDelta, boolean traded) {}

    /**
     * @param signal        the strategy's target position for the next bar, in [-1, 1] (NaN = flat)
     * @param decisionPrice price the decision and share count are based on
     * @param execPrice     price the entry / exit is recorded at (the broker's fill once known; equal to the
     *                      decision price when planning)
     * @param forcedReason  non-null forces the position flat with this exit reason (REMOVED, FLATTEN)
     */
    public static Transition apply(State s, double signal, double decisionPrice, double execPrice, Instant now,
                                   Params p, String forcedReason) {
        if (!(decisionPrice > 0) || !(execPrice > 0)) return new Transition(s, List.of(), 0, false);

        int wantDir = Double.isNaN(signal) || Math.abs(signal) < EPS ? 0 : (signal > 0 ? 1 : -1);
        if (!p.allowShort() && wantDir < 0) wantDir = 0;
        double mag = Math.min(1.0, Math.abs(Double.isNaN(signal) ? 0 : signal));

        int blocked = s.blockedDir();
        String closeReason = SIGNAL;
        if (forcedReason != null) {
            wantDir = 0;
            blocked = 0;
            closeReason = forcedReason;
        } else {
            if (blocked != 0 && wantDir != blocked) blocked = 0;     // the strategy moved on: re-arm entry
            if (blocked != 0) wantDir = 0;                             // still asking for the stopped-out direction
            if (s.direction() != 0 && wantDir == s.direction() && s.entryPrice() > 0) {
                double ret = s.direction() * (decisionPrice / s.entryPrice() - 1);
                if (p.stopLossPct() > 0 && ret <= -p.stopLossPct() / 100.0) {
                    wantDir = 0; blocked = s.direction(); closeReason = STOP_LOSS;
                } else if (p.takeProfitPct() > 0 && ret >= p.takeProfitPct() / 100.0) {
                    wantDir = 0; blocked = s.direction(); closeReason = TAKE_PROFIT;
                }
            }
        }

        double wantQty = wantDir == 0 ? 0 : p.allocationUsd() * mag * p.positionSize() / decisionPrice;
        if (wantDir != 0 && !(wantQty > 0)) wantDir = 0;               // zero allocation: nothing to hold

        List<ClosedTrade> closed = new ArrayList<>();
        double realized = s.realizedPnl();
        int dir = s.direction();
        double qty = s.qty();
        double entry = s.entryPrice();
        Instant entryTime = s.entryTime();
        boolean traded = false;

        boolean sameDir = dir != 0 && dir == wantDir;
        boolean resize = sameDir && Math.abs(wantQty - qty) / qty > RESIZE_TOLERANCE;
        if (dir != 0 && (!sameDir || resize)) {                         // close what we hold
            ClosedTrade t = close(dir, qty, entryTime, entry, now, execPrice, !sameDir ? closeReason : RESIZE);
            closed.add(t);
            realized += t.pnlUsd();
            dir = 0; qty = 0; entry = 0; entryTime = null;
            traded = true;
        }
        if (dir == 0 && wantDir != 0) {                                 // open the wanted position
            dir = wantDir; qty = wantQty; entry = execPrice; entryTime = now;
            traded = true;
        }

        State next = new State(dir, qty, entry, entryTime, blocked, realized);
        return new Transition(next, closed, next.signedQty() - s.signedQty(), traded);
    }

    private static ClosedTrade close(int dir, double qty, Instant entryTime, double entry, Instant now,
                                     double exit, String reason) {
        double pnl = dir * qty * (exit - entry);
        double ret = entry > 0 ? dir * (exit / entry - 1) * 100 : 0;
        return new ClosedTrade(dir > 0 ? "LONG" : "SHORT", qty, entryTime, entry, now, exit, pnl, ret, reason);
    }

    /** Open profit or loss of a held position at {@code price}. */
    public static double unrealized(State s, double price) {
        return s.direction() == 0 || !(price > 0) ? 0 : s.direction() * s.qty() * (price - s.entryPrice());
    }
}
