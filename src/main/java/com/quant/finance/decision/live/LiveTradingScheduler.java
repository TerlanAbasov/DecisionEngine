package com.quant.finance.decision.live;

import com.quant.finance.decision.live.LiveTradingEngine.Mode;
import com.quant.finance.decision.live.LiveTradingEngine.Trigger;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the paper-trading cycle on the saved settings' schedule (enabling starts it, disabling stops it, a new interval re-plans it), one cycle at a time:
 * a scheduled cycle that finds one running is skipped, a manual one is refused.
 */
@Component
@Slf4j
public class LiveTradingScheduler {

    /** A manual action was refused because a cycle is already running. */
    public static class BusyException extends RuntimeException {
        public BusyException() { super("A paper-trading cycle is already running — try again in a moment."); }
    }

    private final LiveTradingEngine engine;
    private final LiveConfigService config;
    private final ThreadPoolTaskScheduler scheduler;
    private final AtomicBoolean busy = new AtomicBoolean();
    private ScheduledFuture<?> schedule;
    private volatile Instant nextRun;
    private volatile Long runningCycleSince;

    public LiveTradingScheduler(LiveTradingEngine engine, LiveConfigService config) {
        this.engine = engine;
        this.config = config;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(2);
        this.scheduler.setThreadNamePrefix("live-trading-");
        this.scheduler.setDaemon(true);
        this.scheduler.initialize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            plan(config.current());
        } catch (RuntimeException e) {
            log.warn("Paper trading: could not start the schedule: {}", e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }

    @EventListener
    public void onConfigChanged(LiveConfigChanged e) {
        plan(e.settings());
    }

    /** (Re)creates the schedule to match {@code s}. */
    synchronized void plan(LiveSettings s) {
        if (schedule != null) { schedule.cancel(false); schedule = null; }
        nextRun = null;
        if (!s.enabled()) {
            log.info("Paper trading: schedule off");
            return;
        }
        Duration every = Duration.ofSeconds(s.intervalSeconds());
        nextRun = Instant.now().plus(Duration.ofSeconds(Math.min(10, s.intervalSeconds())));
        schedule = scheduler.scheduleWithFixedDelay(this::scheduledRun, Instant.now().plus(Duration.ofSeconds(Math.min(10, s.intervalSeconds()))), every);
        log.info("Paper trading: schedule on, every {} s ({}){}", s.intervalSeconds(), s.dryRun() ? "dry run" : "sending orders",
                s.marketHoursOnly() ? ", market hours only" : "");
    }

    private void scheduledRun() {
        if (!busy.compareAndSet(false, true)) { log.info("Paper trading: previous cycle still running — skipping this tick"); return; }
        try {
            LiveSettings cfg = config.current();
            if (!cfg.enabled()) return;
            runningCycleSince = System.currentTimeMillis();
            engine.runCycle(cfg, Trigger.SCHEDULED, Mode.NORMAL);
        } catch (RuntimeException e) {
            log.error("Paper trading: scheduled cycle crashed", e);
        } finally {
            finished();
            nextRun = config.current().enabled() ? Instant.now().plusSeconds(config.current().intervalSeconds()) : null;
        }
    }

    /**
     * Starts one cycle now in the background, whether or not the job is enabled, with the saved settings.
     *
     * @throws BusyException a cycle is already running
     */
    public void runNow() {
        startBackground("manual run", () -> engine.runCycle(config.current(), Trigger.MANUAL, Mode.NORMAL));
    }

    /**
     * Switches the job off and, in the background, closes every position it holds (account and ledger) with one flatten cycle.
     * Needs the market open when "market hours only" is set; throws {@link BusyException} while a cycle runs.
     */
    public void flatten() {
        startBackground("flatten", () -> {
            config.disable();                                    // first, so no scheduled cycle re-opens anything
            engine.runCycle(config.current(), Trigger.MANUAL, Mode.FLATTEN);
        });
    }

    private void startBackground(String what, Runnable work) {
        if (!busy.compareAndSet(false, true)) throw new BusyException();
        runningCycleSince = System.currentTimeMillis();
        try {
            scheduler.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    log.error("Paper trading: {} crashed", what, e);
                } finally {
                    finished();
                }
            });
        } catch (RuntimeException e) {
            finished();
            throw e;
        }
    }

    private void finished() {
        runningCycleSince = null;
        busy.set(false);
    }

    public boolean cycleRunning() { return busy.get(); }

    public Instant nextRun() { return nextRun; }

    public Long runningSinceMillis() { return runningCycleSince; }
}
