package com.quant.finance.decision.job;

import com.quant.finance.decision.dto.Dtos.JobItemDto;
import com.quant.finance.decision.dto.Dtos.JobStepDto;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live progress of one backtest job, written by the job and compute threads and read by HTTP threads (atomic / volatile counters, copy-on-write lists);
 * four steps that can overlap, each with its own counter, weighted into an overall percentage. Callers that don't track progress pass a throw-away instance.
 */
public final class JobProgress {

    public enum Step { PREPARE, LOAD, COMPUTE, SAVE }

    enum State { PENDING, ACTIVE, DONE }

    private enum ItemState { PENDING, RUNNING, DONE, FAILED, NODATA }

    private static final class StepState {
        final String label;
        final int weight;
        volatile State state = State.PENDING;
        final AtomicInteger done = new AtomicInteger();
        volatile int total;
        volatile String detail;

        StepState(String label, int weight) { this.label = label; this.weight = weight; }
    }

    private static final class Item {
        final String name;
        volatile ItemState state = ItemState.PENDING;
        final AtomicInteger done = new AtomicInteger();

        Item(String name) { this.name = name; }
    }

    /** Immutable view handed to the API layer. */
    public record Snapshot(int percent, List<JobStepDto> steps, List<JobItemDto> symbols,
                           List<JobItemDto> strategies, List<String> running) {}

    private final Map<Step, StepState> steps = new EnumMap<>(Step.class);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Map<String, Item> symbols = Map.of();
    private volatile Map<String, Item> strategies = Map.of();
    private volatile int perSymbolTotal;

    public JobProgress(JobKind kind) {
        // Weights follow where the time really goes, so the bar moves steadily instead of stalling: a batch is
        // dominated by saving hundreds of thousands of trades (compute is parallel and takes seconds), an
        // ensemble by loading bars (it saves nothing), a single run splits about evenly.
        String[] labels;
        int[] weights;
        switch (kind) {
            case RUN_ALL -> { labels = new String[] {"Prepare batch", "Load market data", "Run strategies", "Save results"};
                              weights = new int[] {2, 8, 15, 75}; }
            case ENSEMBLE -> { labels = new String[] {"Prepare legs", "Load market data", "Run strategy legs", "Blend legs"};
                               weights = new int[] {3, 40, 50, 7}; }
            case PAIRS -> { labels = new String[] {"Prepare pair", "Load market data", "Run backtest", "Save results"};
                            weights = new int[] {3, 30, 30, 37}; }
            default -> { labels = new String[] {"Prepare run", "Load market data", "Run backtest", "Save results"};
                         weights = new int[] {3, 30, 30, 37}; }
        }
        for (Step s : Step.values()) steps.put(s, new StepState(labels[s.ordinal()], weights[s.ordinal()]));
    }

    // ---- cancellation ---------------------------------------------------------------------------

    public void requestCancel() { cancelled.set(true); }

    public boolean isCancelled() { return cancelled.get(); }

    /** Call at safe points; aborts the job (and rolls its transaction back) once a stop was requested. */
    public void checkCancelled() {
        if (cancelled.get()) throw new JobCancelledException();
    }

    // ---- steps ----------------------------------------------------------------------------------

    /** Starts a step expecting {@code total} units of work (0 = a single instantaneous unit). */
    public void begin(Step step, int total, String detail) {
        StepState s = steps.get(step);
        s.total = Math.max(0, total);
        s.detail = detail;
        s.done.set(0);
        s.state = State.ACTIVE;
    }

    public void advance(Step step, String detail) {
        StepState s = steps.get(step);
        s.done.incrementAndGet();
        if (detail != null) s.detail = detail;
    }

    public void detail(Step step, String detail) { steps.get(step).detail = detail; }

    public void complete(Step step) {
        StepState s = steps.get(step);
        s.done.set(s.total);
        s.state = State.DONE;
    }

    /** The job ended successfully: no step may be left looking half-done. */
    public void finishAll() {
        for (StepState s : steps.values()) { s.done.set(s.total); s.state = State.DONE; }
        for (Item i : strategies.values()) if (i.state == ItemState.PENDING || i.state == ItemState.RUNNING) i.state = ItemState.DONE;
    }

    // ---- symbols --------------------------------------------------------------------------------

    public void planSymbols(List<String> names) { symbols = plan(names); }

    /** A symbol's bars were loaded ({@code hasData} false: nothing cached for it); advances the load step. */
    public void symbolLoaded(String symbol, boolean hasData) {
        Item i = symbols.get(symbol);
        if (i != null) i.state = hasData ? ItemState.DONE : ItemState.NODATA;
        StepState s = steps.get(Step.LOAD);
        int n = s.done.incrementAndGet();
        s.detail = symbol + " (" + (s.total > 0 ? Math.min(n, s.total) : n) + "/" + s.total + ")";
    }

    /** Starts the compute step; every loaded symbol will be computed {@code unitsPerSymbol} times (once per strategy). */
    public void beginCompute(int totalUnits, int unitsPerSymbol, String detail) {
        perSymbolTotal = Math.max(0, unitsPerSymbol);
        begin(Step.COMPUTE, totalUnits, detail);
    }

    /** One (strategy, symbol) unit finished computing. */
    public void symbolComputed(String symbol) {
        Item i = symbols.get(symbol);
        if (i != null) i.done.incrementAndGet();
        StepState s = steps.get(Step.COMPUTE);
        s.done.incrementAndGet();
    }

    // ---- strategies -----------------------------------------------------------------------------

    public void planStrategies(List<String> names) { strategies = plan(names); }

    public void strategyStarted(String name) {
        Item i = strategies.get(name);
        if (i != null) i.state = ItemState.RUNNING;
    }

    public void strategyFinished(String name, boolean ok) {
        Item i = strategies.get(name);
        if (i != null) i.state = ok ? ItemState.DONE : ItemState.FAILED;
    }

    private static Map<String, Item> plan(List<String> names) {
        Map<String, Item> m = new LinkedHashMap<>();
        for (String n : names) m.putIfAbsent(n, new Item(n));
        return m;
    }

    // ---- reading --------------------------------------------------------------------------------

    public Snapshot snapshot() {
        List<JobStepDto> stepDtos = new ArrayList<>();
        double weighted = 0, weightSum = 0;
        for (Step key : Step.values()) {
            StepState s = steps.get(key);
            State state = s.state;
            int total = s.total;
            int done = Math.min(s.done.get(), total);
            weightSum += s.weight;
            weighted += s.weight * (state == State.DONE ? 1.0
                    : state == State.ACTIVE && total > 0 ? (double) done / total : 0.0);
            stepDtos.add(new JobStepDto(key.name().toLowerCase(), s.label, state.name().toLowerCase(),
                    state == State.DONE ? total : done, total, s.detail));
        }
        int percent = (int) Math.floor(100 * weighted / weightSum);

        int unitsPerSymbol = perSymbolTotal;
        List<JobItemDto> symbolDtos = new ArrayList<>();
        for (Item i : symbols.values()) {
            boolean loaded = i.state == ItemState.DONE;
            symbolDtos.add(new JobItemDto(i.name, i.state.name().toLowerCase(),
                    loaded ? Math.min(i.done.get(), unitsPerSymbol) : 0, loaded ? unitsPerSymbol : 0));
        }
        List<JobItemDto> strategyDtos = new ArrayList<>();
        List<String> running = new ArrayList<>();
        for (Item i : strategies.values()) {
            ItemState st = i.state;
            strategyDtos.add(new JobItemDto(i.name, st.name().toLowerCase(), 0, 0));
            if (st == ItemState.RUNNING) running.add(i.name);
        }
        return new Snapshot(percent, stepDtos, symbolDtos, strategyDtos, running);
    }

    /**
     * Snapshot to store once the job ended: COMPLETED shows every step done; otherwise the steps stay as
     * they stood, and strategies caught mid-run count as failed (FAILED) or as not finished (CANCELLED).
     */
    public Snapshot finalSnapshot(JobStatus status) {
        if (status == JobStatus.COMPLETED) {
            finishAll();
            Snapshot s = snapshot();
            return new Snapshot(100, s.steps(), s.symbols(), s.strategies(), s.running());
        }
        for (Item i : strategies.values())
            if (i.state == ItemState.RUNNING) i.state = status == JobStatus.FAILED ? ItemState.FAILED : ItemState.PENDING;
        Snapshot s = snapshot();
        return new Snapshot(s.percent(), s.steps(), s.symbols(), s.strategies(), List.of());
    }
}
