package com.quant.finance.decision.job;

import com.quant.finance.decision.config.ExecutorConfig;
import com.quant.finance.decision.domain.BacktestJobEntity;
import com.quant.finance.decision.dto.Dtos.JobDto;
import com.quant.finance.decision.repository.BacktestJobRepository;
import com.quant.finance.decision.service.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.Map;

/**
 * Runs backtests in the background so the UI can leave the page and come back to a run that is
 * still going (or has finished). One job runs at a time: backtests are CPU- and heap-heavy, and a
 * second start while one is active is refused with the active job so the caller can attach to it.
 *
 * <p>Live jobs are served from memory (their progress is updated from compute threads); every job is
 * also stored in {@code backtest_job} (lifecycle at start, snapshot + result at the end) so a finished
 * job stays readable after it leaves memory or the backend restarts. A job a restart left RUNNING is
 * marked FAILED on startup. Assumes a single backend instance.
 */
@Service
@Slf4j
public class BacktestJobService {

    private static final Duration RETENTION = Duration.ofDays(14);
    private static final int KEEP_IN_MEMORY = 20;
    static final String INTERRUPTED = "Interrupted: the backend restarted while this backtest was running.";

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final JobKind kind;
        final String title;
        final Instant createdAt = Instant.now();
        final JobProgress progress;
        volatile Instant startedAt;
        volatile Instant finishedAt;
        volatile String error;
        volatile Object result;
        volatile JobProgress.Snapshot finalSnapshot;
        /** Written last, so a reader that sees a terminal status also sees everything above. */
        volatile JobStatus status = JobStatus.RUNNING;

        Job(JobKind kind, String title) {
            this.kind = kind;
            this.title = title;
            this.progress = new JobProgress(kind);
        }
    }

    private final BacktestJobRepository repo;
    private final JsonCodec json;
    private final ExecutorService jobExecutor;
    private final Object submitLock = new Object();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public BacktestJobService(BacktestJobRepository repo, JsonCodec json,
                              @Qualifier(ExecutorConfig.BACKTEST_JOB_EXECUTOR) ExecutorService jobExecutor) {
        this.repo = repo;
        this.json = json;
        this.jobExecutor = jobExecutor;
    }

    /**
     * Starts {@code work} in the background and returns immediately.
     *
     * @param request what was asked for; stored with the job for reference only
     * @throws JobConflictException another backtest is still running
     */
    public JobDto submit(JobKind kind, String title, Object request, Function<JobProgress, Object> work) {
        Job job = new Job(kind, title);
        synchronized (submitLock) {
            Job active = activeJob();
            if (active != null) throw new JobConflictException(toDto(active));
            jobs.put(job.id, job);
            try {
                jobExecutor.execute(() -> execute(job, request, work));
            } catch (RuntimeException e) {          // e.g. the executor is shutting down
                jobs.remove(job.id);
                throw e;
            }
        }
        log.info("Job {} ({}) submitted: {}", job.id, kind, title);
        return toDto(job);
    }

    /** The job's current state; the result is included once it COMPLETED. */
    public JobDto get(String id) {
        Job job = jobs.get(id);
        if (job != null) return toDto(job);
        return repo.findById(id).map(this::toDto)
                .orElseThrow(() -> new NoSuchElementException("No backtest job " + id));
    }

    /** Jobs still running (without results), newest first — at most one, by construction. */
    public List<JobDto> active() {
        List<JobDto> out = new ArrayList<>();
        jobs.values().stream().filter(j -> j.status == JobStatus.RUNNING)
                .sorted(Comparator.comparing((Job j) -> j.createdAt).reversed())
                .forEach(j -> out.add(toDto(j, false)));
        return out;
    }

    /**
     * Asks the job to stop at its next safe point (between symbols / strategies); it then ends
     * CANCELLED and nothing it was saving is kept. Already-finished jobs are returned as they are.
     */
    public JobDto cancel(String id) {
        Job job = jobs.get(id);
        if (job != null && job.status == JobStatus.RUNNING) {
            job.progress.requestCancel();
            log.info("Job {}: cancel requested", id);
        }
        return get(id);
    }

    // ---- execution -----------------------------------------------------------------------------

    private void execute(Job job, Object request, Function<JobProgress, Object> work) {
        job.startedAt = Instant.now();
        insertRow(job, request);    // same thread as updateRow below, so the two writes cannot overtake each other
        JobStatus outcome;
        try {
            job.result = work.apply(job.progress);
            outcome = JobStatus.COMPLETED;
        } catch (Throwable t) {
            Throwable root = unwrap(t);
            if (root instanceof JobCancelledException) {
                outcome = JobStatus.CANCELLED;
            } else {
                outcome = JobStatus.FAILED;
                job.error = describe(root);
                log.error("Job {} ({}) failed: {}", job.id, job.kind, job.error, root);
            }
        }
        job.finalSnapshot = job.progress.finalSnapshot(outcome);
        job.finishedAt = Instant.now();
        job.status = outcome;
        log.info("Job {} ({}) {} in {} ms", job.id, job.kind, outcome,
                Duration.between(job.startedAt, job.finishedAt).toMillis());
        try {
            updateRow(job);
        } finally {
            trimMemory();
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable root = t;
        while ((root instanceof CompletionException || root instanceof java.util.concurrent.ExecutionException)
                && root.getCause() != null) root = root.getCause();
        return root;
    }

    /** What the user sees: the message of an expected input error, otherwise the exception's type as well. */
    private static String describe(Throwable t) {
        if (t instanceof OutOfMemoryError)
            return "Out of memory — try fewer symbols or strategies, or a shorter date range.";
        String msg = t.getMessage();
        if (t instanceof IllegalStateException || t instanceof IllegalArgumentException
                || t instanceof NoSuchElementException) return msg == null ? t.getClass().getSimpleName() : msg;
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private Job activeJob() {
        for (Job j : jobs.values()) if (j.status == JobStatus.RUNNING) return j;
        return null;
    }

    /** Finished jobs live in the database; keep only the most recent few in memory. */
    private void trimMemory() {
        List<Job> finished = jobs.values().stream().filter(j -> j.status.isTerminal())
                .sorted(Comparator.comparing((Job j) -> j.finishedAt).reversed()).toList();
        for (int i = KEEP_IN_MEMORY; i < finished.size(); i++) jobs.remove(finished.get(i).id);
    }

    // ---- DTO mapping ---------------------------------------------------------------------------

    private JobDto toDto(Job j) { return toDto(j, true); }

    private JobDto toDto(Job j, boolean withResult) {
        JobStatus status = j.status;                       // read first: terminal implies the fields below are set
        JobProgress.Snapshot snap = status.isTerminal() && j.finalSnapshot != null ? j.finalSnapshot : j.progress.snapshot();
        Instant from = j.startedAt != null ? j.startedAt : j.createdAt;
        Instant to = j.finishedAt != null ? j.finishedAt : Instant.now();
        return new JobDto(j.id, j.kind.name(), status.name(), j.title, j.createdAt, j.startedAt, j.finishedAt,
                Math.max(0, Duration.between(from, to).toMillis()), snap.percent(), snap.steps(), snap.symbols(),
                snap.strategies(), snap.running(), j.progress.isCancelled() && status == JobStatus.RUNNING,
                j.error, withResult && status == JobStatus.COMPLETED ? j.result : null);
    }

    private JobDto toDto(BacktestJobEntity e) {
        JobStatus status = JobStatus.valueOf(e.getStatus());
        JobProgress.Snapshot snap = json.read(e.getProgressJson(), JobProgress.Snapshot.class);
        Instant from = e.getStartedAt() != null ? e.getStartedAt() : e.getCreatedAt();
        Instant to = e.getFinishedAt() != null ? e.getFinishedAt() : Instant.now();
        Object result = status == JobStatus.COMPLETED ? json.read(e.getResultJson(), Object.class) : null;
        return new JobDto(e.getId(), e.getKind(), status.name(), e.getTitle(), e.getCreatedAt(), e.getStartedAt(),
                e.getFinishedAt(), Math.max(0, Duration.between(from, to).toMillis()),
                status == JobStatus.COMPLETED ? 100 : snap == null ? 0 : snap.percent(),
                snap == null ? List.of() : snap.steps(), snap == null ? List.of() : snap.symbols(),
                snap == null ? List.of() : snap.strategies(), List.of(), false, e.getError(), result);
    }

    // ---- persistence (best effort: a database hiccup must not fail or lose a running backtest) --

    private void insertRow(Job j, Object request) {
        try {
            BacktestJobEntity e = new BacktestJobEntity();
            e.setId(j.id);
            e.setKind(j.kind.name());
            e.setStatus(JobStatus.RUNNING.name());
            e.setTitle(j.title);
            e.setRequestJson(request == null ? null : json.write(request));
            e.setCreatedAt(j.createdAt);
            repo.save(e);
        } catch (RuntimeException ex) {
            log.warn("Job {}: could not store it ({}); it will still run", j.id, ex.toString());
        }
    }

    private void updateRow(Job j) {
        try {
            BacktestJobEntity e = repo.findById(j.id).orElseGet(() -> {
                BacktestJobEntity n = new BacktestJobEntity();
                n.setId(j.id);
                n.setKind(j.kind.name());
                n.setTitle(j.title);
                n.setCreatedAt(j.createdAt);
                return n;
            });
            e.setStatus(j.status.name());
            e.setStartedAt(j.startedAt);
            e.setFinishedAt(j.finishedAt);
            e.setError(j.error);
            e.setProgressJson(json.write(j.finalSnapshot));
            e.setResultJson(j.status == JobStatus.COMPLETED && j.result != null ? json.write(j.result) : null);
            repo.save(e);
        } catch (RuntimeException ex) {
            log.warn("Job {}: could not store its outcome ({}); it stays readable from memory", j.id, ex.toString());
        }
    }

    /** A job the previous process left RUNNING can never finish — say so instead of letting the UI wait forever. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        try {
            Instant now = Instant.now();
            List<BacktestJobEntity> orphans = repo.findByStatus(JobStatus.RUNNING.name());
            for (BacktestJobEntity e : orphans) {
                e.setStatus(JobStatus.FAILED.name());
                e.setError(INTERRUPTED);
                e.setFinishedAt(now);
            }
            if (!orphans.isEmpty()) {
                repo.saveAll(orphans);
                log.warn("Backtest jobs: marked {} job(s) left running by the last shutdown as failed", orphans.size());
            }
            long pruned = repo.deleteByCreatedAtBefore(now.minus(RETENTION));
            if (pruned > 0) log.info("Backtest jobs: pruned {} job(s) older than {} days", pruned, RETENTION.toDays());
        } catch (RuntimeException ex) {
            log.warn("Backtest jobs: startup cleanup failed: {}", ex.toString());
        }
    }
}
