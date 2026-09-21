package com.quant.finance.decision.live;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One strategy's virtual position in one symbol and how it reacts to a new signal (Alpaca nets per symbol, so each strategy's own position and P&amp;L live here).
 * Pure functions mirroring the backtester: signals in [-1, 1]; a stop-loss / take-profit closes and keeps it closed until the signal changes direction; shorts can be off.
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

    /** What the strategy wants to hold after this signal: a direction (0 = flat), the direction now blocked, and why anything held is closed. */
    private record Target(int direction, int blockedDir, String closeReason) {}

    /**
     * Applies a signal in [-1, 1] (NaN = flat): sizes and decides at {@code decisionPrice}, records fills at {@code execPrice} (the broker's fill once known),
     * and a non-null {@code forcedReason} (REMOVED, FLATTEN) forces the position flat with that exit reason.
     */
    public static Transition apply(State s, double signal, double decisionPrice, double execPrice, Instant now,
                                   Params p, String forcedReason) {
        if (!(decisionPrice > 0) || !(execPrice > 0)) return new Transition(s, List.of(), 0, false);

        Target target = target(s, signal, decisionPrice, p, forcedReason);
        int wantDir = target.direction();
        double wantQty = wantDir == 0 ? 0 : p.allocationUsd() * strength(signal) * p.positionSize() / decisionPrice;
        if (!(wantQty > 0)) wantDir = 0;                               // zero allocation: nothing to hold

        boolean holding = s.direction() != 0;
        boolean sameDir = holding && s.direction() == wantDir;
        boolean resize = sameDir && Math.abs(wantQty - s.qty()) / s.qty() > RESIZE_TOLERANCE;

        List<ClosedTrade> closed = new ArrayList<>();
        double realized = s.realizedPnl();
        if (holding && (!sameDir || resize)) {                          // close what we hold
            ClosedTrade t = close(s, now, execPrice, sameDir ? RESIZE : target.closeReason());
            closed.add(t);
            realized += t.pnlUsd();
            holding = false;
        }

        State next;
        if (holding) next = new State(s.direction(), s.qty(), s.entryPrice(), s.entryTime(), target.blockedDir(), realized);
        else if (wantDir != 0) next = new State(wantDir, wantQty, execPrice, now, target.blockedDir(), realized);   // open the wanted position
        else next = new State(0, 0, 0, null, target.blockedDir(), realized);
        boolean traded = !closed.isEmpty() || (!holding && wantDir != 0);
        return new Transition(next, closed, next.signedQty() - s.signedQty(), traded);
    }

    /** The direction the signal asks for (0 when short is off or the signal is flat), then the stop-loss / take-profit rules over it. */
    private static Target target(State s, double signal, double price, Params p, String forcedReason) {
        if (forcedReason != null) return new Target(0, 0, forcedReason);

        int wantDir = Double.isNaN(signal) || Math.abs(signal) < EPS ? 0 : (signal > 0 ? 1 : -1);
        if (!p.allowShort() && wantDir < 0) wantDir = 0;
        int blocked = s.blockedDir();
        if (blocked != 0 && wantDir != blocked) blocked = 0;           // the strategy moved on: re-arm entry
        if (blocked != 0) wantDir = 0;                                  // still asking for the stopped-out direction

        if (s.direction() != 0 && wantDir == s.direction() && s.entryPrice() > 0) {
            double ret = s.direction() * (price / s.entryPrice() - 1);
            if (p.stopLossPct() > 0 && ret <= -p.stopLossPct() / 100.0) return new Target(0, s.direction(), STOP_LOSS);
            if (p.takeProfitPct() > 0 && ret >= p.takeProfitPct() / 100.0) return new Target(0, s.direction(), TAKE_PROFIT);
        }
        return new Target(wantDir, blocked, SIGNAL);
    }

    /** How much of the full allocation the signal asks for, in [0, 1]. */
    private static double strength(double signal) {
        return Double.isNaN(signal) ? 0 : Math.min(1.0, Math.abs(signal));
    }

    private static ClosedTrade close(State s, Instant now, double exit, String reason) {
        double pnl = s.direction() * s.qty() * (exit - s.entryPrice());
        double ret = s.entryPrice() > 0 ? s.direction() * (exit / s.entryPrice() - 1) * 100 : 0;
        return new ClosedTrade(s.direction() > 0 ? "LONG" : "SHORT", s.qty(), s.entryTime(), s.entryPrice(), now, exit, pnl, ret, reason);
    }

    /** Open profit or loss of a held position at {@code price}. */
    public static double unrealized(State s, double price) {
        return s.direction() == 0 || !(price > 0) ? 0 : s.direction() * s.qty() * (price - s.entryPrice());
    }
}
