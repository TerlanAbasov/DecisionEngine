package com.quant.finance.decision.live;

import com.quant.finance.decision.entity.LiveCycleEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

/** What one cycle looked at and did, counted as it goes and written onto the cycle's record at the end. */
final class CycleStats {
    private static final int MAX_NOTES_SHOWN = 5;

    int symbols, strategies, signals, ordersPlanned, ordersFilled, ordersFailed, tradesClosed, errors;
    private final List<String> notes = new ArrayList<>();

    /** Something worth telling the user that is not a failure. */
    void note(String text) { notes.add(text); }

    /** A failure the cycle carried on past. */
    void error(String text) {
        errors++;
        notes.add(text);
    }

    void writeTo(LiveCycleEntity cycle) {
        cycle.setSymbols(symbols);
        cycle.setStrategies(strategies);
        cycle.setSignals(signals);
        cycle.setOrdersPlanned(ordersPlanned);
        cycle.setOrdersFilled(ordersFilled);
        cycle.setOrdersFailed(ordersFailed);
        cycle.setTradesClosed(tradesClosed);
        cycle.setErrors(errors);
    }

    String summary(boolean dryRun) {
        String orders = plural(ordersPlanned, "order")
                + (dryRun ? " planned (dry run: none sent)" : " (" + ordersFilled + " filled, " + ordersFailed + " failed)");
        String text = signals + " signals, " + orders + ", " + plural(tradesClosed, "trade") + " closed";
        if (errors > 0) text += ", " + plural(errors, "error");
        return notes.isEmpty() ? text : text + ". " + String.join("; ", notes.subList(0, Math.min(MAX_NOTES_SHOWN, notes.size())));
    }

    /** The failure's message, or its class name when it has none. */
    static String describe(Throwable e) {
        Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static String plural(int n, String noun) { return n + " " + noun + (n == 1 ? "" : "s"); }
}
