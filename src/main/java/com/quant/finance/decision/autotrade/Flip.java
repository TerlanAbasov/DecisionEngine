package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.autotrade.TradeCommand.Side;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.autotrade.SignalEvaluator.Reading;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** A strategy that turned LONG (BUY) or SHORT (SELL) on the bar that opened at {@code barOpen}, whose last price was {@code close}. */
record Flip(String strategy, String symbol, Timeframe timeframe, Side side, Instant barOpen, double close) {

    /** A signal older than this many bars is not acted on: something (a restart, an outage) made it late, and the market has moved on. */
    static final int STALE_AFTER_BARS = 2;

    /**
     * The flip in {@code reading}, if any: the strategy now wants a direction the bar before it did not (going flat is not a flip: there is no close command),
     * the bar completed after the job was switched on at {@code enabledSince}, and the signal is not stale at {@code now}.
     */
    static Optional<Flip> detect(String strategy, String symbol, Timeframe timeframe, Reading reading, Instant enabledSince, Instant now) {
        int direction = (int) Math.signum(reading.signal());
        if (direction == 0 || direction == (int) Math.signum(reading.previousSignal())) return Optional.empty();

        Instant closedAt = BarClock.closesAt(reading.barOpen(), timeframe);
        if (enabledSince == null || !closedAt.isAfter(enabledSince)) return Optional.empty();
        if (Duration.between(closedAt, now).getSeconds() > STALE_AFTER_BARS * BarClock.barSeconds(timeframe)) return Optional.empty();
        return Optional.of(new Flip(strategy, symbol, timeframe, direction > 0 ? Side.BUY : Side.SELL, reading.barOpen(), reading.close()));
    }
}
