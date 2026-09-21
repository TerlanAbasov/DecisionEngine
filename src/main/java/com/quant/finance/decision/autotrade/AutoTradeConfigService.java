package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.service.StrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

/** Loads and saves the auto-trading job's settings (one row), validating every change; the job reads {@link #current()} before every command it sends. */
@Service
@Slf4j
@RequiredArgsConstructor
public class AutoTradeConfigService {

    private final AutoTradeConfigRepository repo;
    private final StrategyService strategies;
    private final CommandSender sender;
    private final Clock clock;
    private volatile AutoTradeSettings cached;

    public AutoTradeSettings current() {
        AutoTradeSettings settings = cached;
        return settings != null ? settings : load();
    }

    private synchronized AutoTradeSettings load() {
        if (cached == null) {
            cached = repo.findById(1L).map(AutoTradeConfigService::toSettings).orElseGet(() -> {
                AutoTradeSettings defaults = AutoTradeSettings.defaults();
                repo.save(toEntity(defaults));
                return defaults;
            });
        }
        return cached;
    }

    /** Validates and saves the form fields of {@code edited}; whether the job is on is not touched. */
    public synchronized AutoTradeSettings update(AutoTradeSettings edited) {
        AutoTradeSettings updated = current().editedTo(edited.validated(strategies::isStrategy));
        save(updated);
        log.info("Auto-trading settings saved: quantity={} {} {}, {} strategies, {} symbols, max {} commands per run",
                updated.quantity(), updated.orderType(), updated.tif(), describe(updated.strategyNames()), describe(updated.symbols()),
                updated.maxCommandsPerRun());
        return updated;
    }

    /** Switches the job on. Only bars that complete from now on can trigger a command. Refused while ExecutionEngine is not configured. */
    public synchronized AutoTradeSettings enable() {
        AutoTradeSettings settings = current();
        if (settings.enabled()) return settings;
        if (!sender.isConfigured())
            throw new IllegalStateException("ExecutionEngine is not configured: set decision.execution-engine.url (env EXECUTION_ENGINE_URL)");
        AutoTradeSettings on = settings.switchedOn(true, clock.instant());
        save(on);
        log.info("Auto-trading switched ON: commands go to ExecutionEngine for bars completing after {}", on.enabledSince());
        return on;
    }

    /** Switches the job off; the job stops before its next command. */
    public synchronized AutoTradeSettings disable() {
        AutoTradeSettings settings = current();
        if (!settings.enabled()) return settings;
        AutoTradeSettings off = settings.switchedOn(false, clock.instant());
        save(off);
        log.info("Auto-trading switched OFF");
        return off;
    }

    private void save(AutoTradeSettings settings) {
        repo.save(toEntity(settings));
        cached = settings;
    }

    private static String describe(List<String> names) {
        return names.isEmpty() ? "all" : String.valueOf(names.size());
    }

    private static AutoTradeSettings toSettings(AutoTradeConfigEntity e) {
        return new AutoTradeSettings(e.isEnabled(), e.getEnabledSince(), e.getQuantity(),
                AutoTradeSettings.OrderType.valueOf(e.getOrderType()), AutoTradeSettings.TimeInForce.valueOf(e.getTif()),
                csv(e.getStrategyNames()), csv(e.getSymbols()), e.getMaxCommandsPerRun());
    }

    private AutoTradeConfigEntity toEntity(AutoTradeSettings s) {
        AutoTradeConfigEntity e = new AutoTradeConfigEntity();
        e.setId(1L);
        e.setEnabled(s.enabled());
        e.setEnabledSince(s.enabledSince());
        e.setQuantity(s.quantity());
        e.setOrderType(s.orderType().name());
        e.setTif(s.tif().name());
        e.setStrategyNames(String.join(",", s.strategyNames()));
        e.setSymbols(String.join(",", s.symbols()));
        e.setMaxCommandsPerRun(s.maxCommandsPerRun());
        e.setUpdatedAt(clock.instant());
        return e;
    }

    private static List<String> csv(String s) {
        return s == null || s.isBlank() ? List.of()
                : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
