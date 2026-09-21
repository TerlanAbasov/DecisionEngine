package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.service.StrategyService;
import com.quant.finance.decision.service.UniverseService;
import com.quant.finance.decision.strategy.TradingStrategy;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * What the auto-trading job may do; {@code enabledSince} is when the switch was last turned on, and only bars completed after it can trigger a command.
 * Empty {@code strategyNames} / {@code symbols} mean every enabled strategy / the whole universe; {@code maxCommandsPerRun} caps a burst of flips at one bar close.
 */
public record AutoTradeSettings(boolean enabled, Instant enabledSince, double quantity, OrderType orderType, TimeInForce tif,
                                List<String> strategyNames, List<String> symbols, int maxCommandsPerRun) {

    public enum OrderType { MKT, LMT }

    public enum TimeInForce { DAY, GTC }

    private static final Pattern SYMBOL = Pattern.compile("[A-Z][A-Z0-9.\\-]{0,9}");
    static final double MAX_QUANTITY = 1_000_000;
    static final int MAX_COMMANDS_PER_RUN = 500;

    /** Off, one share per command, market orders for the day. */
    public static AutoTradeSettings defaults() {
        return new AutoTradeSettings(false, null, 1, OrderType.MKT, TimeInForce.DAY, List.of(), List.of(), 25);
    }

    /** The strategies traded: the named ones, or every enabled strategy when none are named. */
    public Map<String, TradingStrategy> strategiesIn(StrategyService service) {
        return strategyNames.isEmpty() ? service.getEnabledStrategies() : service.getStrategies(strategyNames);
    }

    /** The symbols traded: the listed ones, or the whole universe when none are listed. */
    public List<String> symbolsIn(UniverseService universe) {
        return symbols.isEmpty() ? universe.get() : symbols;
    }

    /** These settings switched on (since {@code now}) or off; switching on when already on keeps the original time. */
    AutoTradeSettings switchedOn(boolean on, Instant now) {
        Instant since = !on ? null : enabled ? enabledSince : now;
        return new AutoTradeSettings(on, since, quantity, orderType, tif, strategyNames, symbols, maxCommandsPerRun);
    }

    /** These settings with the form fields of {@code edited}; the switch is left as it is. */
    AutoTradeSettings editedTo(AutoTradeSettings edited) {
        return new AutoTradeSettings(enabled, enabledSince, edited.quantity, edited.orderType, edited.tif,
                edited.strategyNames, edited.symbols, edited.maxCommandsPerRun);
    }

    /** Returns these settings with names normalised, or throws IllegalArgumentException naming the first invalid one. */
    public AutoTradeSettings validated(Predicate<String> knownStrategy) {
        if (Double.isNaN(quantity) || quantity <= 0 || quantity > MAX_QUANTITY)
            throw new IllegalArgumentException("quantity must be more than 0 and at most " + (long) MAX_QUANTITY + " shares");
        if (orderType == null) throw new IllegalArgumentException("orderType is required (MKT or LMT)");
        if (tif == null) throw new IllegalArgumentException("tif is required (DAY or GTC)");
        if (maxCommandsPerRun < 1 || maxCommandsPerRun > MAX_COMMANDS_PER_RUN)
            throw new IllegalArgumentException("maxCommandsPerRun must be between 1 and " + MAX_COMMANDS_PER_RUN);

        List<String> names = cleaned(strategyNames, String::trim, n -> {
            if (!knownStrategy.test(n)) throw new IllegalArgumentException("Unknown strategy: " + n);
        });
        List<String> syms = cleaned(symbols, n -> n.trim().toUpperCase(), n -> {
            if (!SYMBOL.matcher(n).matches()) throw new IllegalArgumentException("Invalid symbol: " + n);
        });
        return new AutoTradeSettings(enabled, enabledSince, quantity, orderType, tif, names, syms, maxCommandsPerRun);
    }

    /** Normalised, without blanks or repeats, each checked by {@code validate}. */
    private static List<String> cleaned(List<String> raw, UnaryOperator<String> normalise, Consumer<String> validate) {
        Set<String> out = new LinkedHashSet<>();
        for (String item : raw == null ? List.<String>of() : raw) {
            String s = item == null ? "" : normalise.apply(item);
            if (s.isEmpty() || out.contains(s)) continue;
            validate.accept(s);
            out.add(s);
        }
        return List.copyOf(out);
    }
}
