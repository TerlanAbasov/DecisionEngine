package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.service.StrategyService;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Loads and saves the auto-trading job's settings (one row), validating every change; the job reads {@link #current()} before every command it sends.
 */
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

    public synchronized AutoTradeSettings update(AutoTradeSettings edited) {
        AutoTradeSettings updated = current().editedTo(edited.validated(strategies::isStrategy));
        save(updated);
        log.info(
                "Auto-trading settings saved: quantity={} {} {}, {} strategies, {} symbols, max {} commands per run",
                updated.quantity(),
                updated.orderType(),
                updated.tif(),
                describe(updated.strategyNames()),
                describe(updated.symbols()),
                updated.maxCommandsPerRun());
        return updated;
    }

    public synchronized AutoTradeSettings enable() {
        AutoTradeSettings settings = current();
        if (settings.enabled()) {
            return settings;
        }

        AutoTradeSettings on = settings.switchedOn(true, clock.instant());
        save(on);
        log.info(
                "Auto-trading switched ON: commands go to ExecutionEngine for bars completing after {}",
                on.enabledSince());
        return on;
    }

    /**
     * Switches the job off; the job stops before its next command.
     */
    public synchronized AutoTradeSettings disable() {
        AutoTradeSettings settings = current();
        if (!settings.enabled()) {
            return settings;
        }
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

    private static AutoTradeSettings toSettings(AutoTradeConfigEntity entity) {
        return new AutoTradeSettings(
                entity.isEnabled(),
                entity.getEnabledSince(),
                entity.getQuantity(),
                AutoTradeSettings.OrderType.valueOf(entity.getOrderType()),
                AutoTradeSettings.TimeInForce.valueOf(entity.getTif()),
                csv(entity.getStrategyNames()),
                csv(entity.getSymbols()),
                entity.getMaxCommandsPerRun());
    }

    private AutoTradeConfigEntity toEntity(AutoTradeSettings settings) {
        AutoTradeConfigEntity entity = new AutoTradeConfigEntity();
        entity.setId(1L);
        entity.setEnabled(settings.enabled());
        entity.setEnabledSince(settings.enabledSince());
        entity.setQuantity(settings.quantity());
        entity.setOrderType(settings.orderType().name());
        entity.setTif(settings.tif().name());
        entity.setStrategyNames(String.join(",", settings.strategyNames()));
        entity.setSymbols(String.join(",", settings.symbols()));
        entity.setMaxCommandsPerRun(settings.maxCommandsPerRun());
        entity.setUpdatedAt(clock.instant());
        return entity;
    }

    private static List<String> csv(String commaSeparated) {
        return commaSeparated == null || commaSeparated.isBlank()
                ? List.of()
                : Arrays.stream(commaSeparated.split(","))
                        .map(String::trim)
                        .filter(x -> !x.isEmpty())
                        .toList();
    }
}
