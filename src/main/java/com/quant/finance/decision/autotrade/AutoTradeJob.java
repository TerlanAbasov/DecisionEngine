package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.autotrade.CommandForwarder.Outcome;
import com.quant.finance.decision.autotrade.SignalScanner.Result;
import com.quant.finance.decision.config.ApplicationProperties;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Every few seconds, while switched on, looks for strategies that changed their mind on a bar that just completed and sends ExecutionEngine a BUY or SELL for each.
 * The tick is cheap: which symbols and timeframes are actually fetched is decided by {@link CheckSchedule}. Ticks never overlap (one thread, fixed delay).
 */
@Component
@Slf4j
public class AutoTradeJob {

    private static final int MIN_TICK_SECONDS = 5;
    private static final Duration START_DELAY = Duration.ofSeconds(15);

    private final AutoTradeConfigService config;
    private final SignalScanner scanner;
    private final CommandForwarder forwarder;
    private final Clock clock;
    private final ApplicationProperties properties;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private volatile boolean running;
    private volatile RunSummary lastRun;

    AutoTradeJob(
            AutoTradeConfigService config,
            SignalScanner scanner,
            CommandForwarder forwarder,
            Clock clock,
            ApplicationProperties properties) {
        this.config = config;
        this.scanner = scanner;
        this.forwarder = forwarder;
        this.clock = clock;
        this.properties = properties;
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("auto-trade-");
        scheduler.setDaemon(true);
        scheduler.initialize();
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        int tickSeconds = tickSeconds();
        scheduler.scheduleWithFixedDelay(
                this::tick, clock.instant().plus(START_DELAY), Duration.ofSeconds(tickSeconds));
        log.info("Auto-trading: checking every {} s while switched on", tickSeconds);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }

    /** One pass; never throws, since a scheduled task that throws is never run again. */
    void tick() {
        try {
            AutoTradeSettings settings = config.current();
            if (!settings.enabled()) {
                return;
            }
            running = true;
            Result scan = scanner.scan(settings, clock.instant());
            Outcome outcome = forwarder.forward(scan.flips());
            if (scan.checked() > 0 || !scan.flips().isEmpty()) {
                lastRun = new RunSummary(
                        clock.instant(),
                        scan.checked(),
                        scan.errors(),
                        scan.flips().size(),
                        outcome.sent(),
                        outcome.rejected(),
                        outcome.failed(),
                        outcome.skipped());
                log.info("Auto-trading: {}", lastRun);
            }
        } catch (RuntimeException e) {
            log.error("Auto-trading: tick failed", e);
        } finally {
            running = false;
        }
    }

    public boolean running() {
        return running;
    }

    public RunSummary lastRun() {
        return lastRun;
    }

    public int tickSeconds() {
        return Math.max(MIN_TICK_SECONDS, properties.getAutotrade().getTickSeconds());
    }
}
