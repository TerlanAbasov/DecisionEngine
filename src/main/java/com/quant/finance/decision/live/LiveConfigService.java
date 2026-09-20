package com.quant.finance.decision.live;

import com.quant.finance.decision.domain.LiveConfigEntity;
import com.quant.finance.decision.repository.LiveConfigRepository;
import com.quant.finance.decision.service.StrategyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Loads and saves the paper-trading job's settings (one row), validating every change. */
@Service
@Slf4j
public class LiveConfigService {

    private final LiveConfigRepository repo;
    private final StrategyService strategies;
    private final ApplicationEventPublisher events;
    private volatile LiveSettings cached;

    public LiveConfigService(LiveConfigRepository repo, StrategyService strategies, ApplicationEventPublisher events) {
        this.repo = repo;
        this.strategies = strategies;
        this.events = events;
    }

    public synchronized LiveSettings current() {
        if (cached == null) {
            cached = repo.findById(1L).map(LiveConfigService::toSettings).orElseGet(() -> {
                LiveSettings d = LiveSettings.defaults();
                repo.save(toEntity(d));
                return d;
            });
        }
        return cached;
    }

    /** Validates, stores and announces the new settings. */
    public synchronized LiveSettings update(LiveSettings requested) {
        LiveSettings v = requested.validated(strategies::isStrategy);
        repo.save(toEntity(v));
        cached = v;
        log.info("Paper trading settings saved: enabled={} dryRun={} every {}s, ${} per slot, timeframe {}",
                v.enabled(), v.dryRun(), v.intervalSeconds(), v.allocationUsd(), v.timeframeMode());
        events.publishEvent(new LiveConfigChanged(v));
        return v;
    }

    /** Turns the job off without touching anything else (used by the flatten action and as a kill switch). */
    public LiveSettings disable() {
        LiveSettings c = current();
        return c.enabled() ? update(new LiveSettings(false, c.dryRun(), c.intervalSeconds(), c.allocationUsd(),
                c.positionSize(), c.allowShort(), c.timeframeMode(), c.lookbackBars(), c.strategyNames(), c.symbols(),
                c.maxGrossUsd(), c.maxOrdersPerCycle(), c.marketHoursOnly(), c.fillTimeoutSeconds(), c.useRiskDefaults())) : c;
    }

    private static LiveSettings toSettings(LiveConfigEntity e) {
        return new LiveSettings(e.isEnabled(), e.isDryRun(), e.getIntervalSeconds(), e.getAllocationUsd(),
                e.getPositionSize(), e.isAllowShort(), e.getTimeframeMode(), e.getLookbackBars(),
                csv(e.getStrategyNames()), csv(e.getSymbols()), e.getMaxGrossUsd(), e.getMaxOrdersPerCycle(),
                e.isMarketHoursOnly(), e.getFillTimeoutSeconds(), e.isUseRiskDefaults());
    }

    private static LiveConfigEntity toEntity(LiveSettings s) {
        LiveConfigEntity e = new LiveConfigEntity();
        e.setId(1L);
        e.setEnabled(s.enabled());
        e.setDryRun(s.dryRun());
        e.setIntervalSeconds(s.intervalSeconds());
        e.setAllocationUsd(s.allocationUsd());
        e.setPositionSize(s.positionSize());
        e.setAllowShort(s.allowShort());
        e.setTimeframeMode(s.timeframeMode());
        e.setLookbackBars(s.lookbackBars());
        e.setStrategyNames(String.join(",", s.strategyNames()));
        e.setSymbols(String.join(",", s.symbols()));
        e.setMaxGrossUsd(s.maxGrossUsd());
        e.setMaxOrdersPerCycle(s.maxOrdersPerCycle());
        e.setMarketHoursOnly(s.marketHoursOnly());
        e.setFillTimeoutSeconds(s.fillTimeoutSeconds());
        e.setUseRiskDefaults(s.useRiskDefaults());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    private static List<String> csv(String s) {
        return s == null || s.isBlank() ? List.of()
                : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
