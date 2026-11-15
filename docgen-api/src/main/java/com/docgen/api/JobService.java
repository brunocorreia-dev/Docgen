package com.docgen.api;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous execution of long AI operations with HTTP polling.
 *
 * <p>Limits: a fixed worker pool, a bounded queue (full queue = 429), a
 * per-user cap on active jobs and a wall-clock timeout per job. Cancelling a
 * queued job removes it before it starts; cancelling a running job interrupts
 * its worker, which aborts an in-flight provider HTTP exchange (provider calls
 * wait interruptibly). Results are persisted in the same transaction as the
 * RUNNING-to-SUCCEEDED transition, so a cancelled, timed-out or deleted job
 * never publishes a result. Jobs left unfinished by a restart are marked FAILED.</p>
 */
@Service
class JobService {
    private static final Logger LOG = LoggerFactory.getLogger(JobService.class);

    /** Work executed by a worker thread; it must not persist anything itself. */
    @FunctionalInterface
    interface Work {
        Outcome run() throws Exception;
    }

    /** A result id and the persistence step run atomically with the success transition. */
    record Outcome(String resultId, Runnable persist) {
    }

    record JobView(
            String id,
            String projectId,
            JobRepository.Type type,
            JobRepository.Status status,
            String provider,
            String model,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant finishedAt,
            String resultId,
            JobError error
    ) {
    }

    record JobError(String code, String message) {
    }

    record JobList(List<JobView> jobs) {
    }

    private final JobRepository jobs;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService watchdog;
    private final Duration timeout;
    private final int maxActivePerUser;
    private final Map<String, Handle> handles = new ConcurrentHashMap<>();
    private final ActivityTracker activity;

    /**
     * Interrupts are delivered only while the worker runs provider code. H2
     * file storage must not be interrupted mid-write, so database steps run
     * with {@code interruptible} cleared.
     */
    private static final class Handle {
        private final Object lock = new Object();
        private volatile Future<?> future;
        private boolean interruptible;
        private boolean cancelRequested;
    }

    JobService(
            JobRepository jobs,
            TransactionTemplate transactions,
            Clock clock,
            DocGenApiProperties properties,
            ActivityTracker activity
    ) {
        this.jobs = jobs;
        this.activity = activity;
        this.transactions = transactions;
        this.clock = clock;
        DocGenApiProperties.Jobs limits = properties.jobs();
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(limits.workers(), limits.workers(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(limits.queueCapacity()), runnable -> {
            Thread thread = new Thread(runnable, "docgen-job-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "docgen-job-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        this.watchdog = scheduler;
        this.timeout = limits.timeout();
        this.maxActivePerUser = limits.maxActivePerUser();
    }

    @PostConstruct
    void recoverInterruptedJobs() {
        int recovered = jobs.failUnfinished("interrupted", "The server stopped before the job finished.",
                clock.instant());
        if (recovered > 0) {
            LOG.info("Marked {} unfinished job(s) from a previous run as failed", recovered);
        }
    }

    synchronized JobView submit(
            String owner,
            String projectId,
            JobRepository.Type type,
            String provider,
            String model,
            Work work
    ) {
        if (jobs.countActive(owner) >= maxActivePerUser) {
            throw new CapacityExceededException("too_many_jobs", "Too many jobs are already queued or running.");
        }
        Instant now = clock.instant();
        JobRepository.JobRecord job = new JobRepository.JobRecord(Ids.random(), owner, projectId, type,
                JobRepository.Status.QUEUED, provider, model, now, now, null, null, null, null, null);
        jobs.insert(job);
        Handle handle = new Handle();
        handles.put(job.id(), handle);
        try {
            handle.future = executor.submit(() -> execute(job.id(), handle, work));
        } catch (RejectedExecutionException full) {
            handles.remove(job.id());
            jobs.delete(job.id());
            throw new CapacityExceededException("queue_full", "The job queue is full. Try again later.");
        }
        return view(job);
    }

    JobView get(String owner, String jobId) {
        return jobs.find(Ids.require(jobId), owner).map(JobService::view).orElseThrow(ResourceNotFoundException::new);
    }

    JobList listForProject(String owner, String projectId) {
        return new JobList(jobs.listForProject(projectId, owner, 50).stream().map(JobService::view).toList());
    }

    JobView cancel(String owner, String jobId) {
        JobRepository.JobRecord job = jobs.find(Ids.require(jobId), owner).orElseThrow(ResourceNotFoundException::new);
        Instant now = clock.instant();
        boolean cancelled = switch (job.status()) {
            case QUEUED -> jobs.finish(job.id(), JobRepository.Status.QUEUED, JobRepository.Status.CANCELLED,
                    "cancelled", "The job was cancelled before it started.", now);
            case RUNNING -> jobs.finish(job.id(), JobRepository.Status.RUNNING, JobRepository.Status.CANCELLED,
                    "cancelled", "The job was cancelled while running.", now);
            default -> false;
        };
        if (!cancelled) {
            throw new ConflictException("job_not_cancellable", "The job has already finished.");
        }
        interrupt(job.id());
        return get(owner, job.id());
    }

    /** Cancels every active job of a project, used before the project is deleted. */
    void cancelProjectJobs(String owner, String projectId) {
        cancelAll(jobs.activeIdsForProject(projectId, owner), "The project was deleted.");
    }

    /** Cancels every active job of the owner, used before all data is erased. */
    void cancelAllJobs(String owner) {
        cancelAll(jobs.activeIds(owner), "All data was erased.");
    }

    private void cancelAll(List<String> ids, String reason) {
        for (String id : ids) {
            Instant now = clock.instant();
            jobs.finish(id, JobRepository.Status.QUEUED, JobRepository.Status.CANCELLED, "cancelled", reason, now);
            jobs.finish(id, JobRepository.Status.RUNNING, JobRepository.Status.CANCELLED, "cancelled", reason, now);
            interrupt(id);
        }
    }

    private void interrupt(String jobId) {
        Handle handle = handles.get(jobId);
        if (handle == null) {
            return;
        }
        synchronized (handle.lock) {
            handle.cancelRequested = true;
            Future<?> future = handle.future;
            if (future != null) {
                future.cancel(handle.interruptible);
                if (future instanceof Runnable runnable) {
                    executor.remove(runnable);
                }
            }
        }
    }

    private static boolean enterProviderCode(Handle handle) {
        synchronized (handle.lock) {
            if (handle.cancelRequested) {
                return false;
            }
            handle.interruptible = true;
            return true;
        }
    }

    private static boolean leaveProviderCode(Handle handle) {
        synchronized (handle.lock) {
            handle.interruptible = false;
            Thread.interrupted();
            return !handle.cancelRequested;
        }
    }

    private void execute(String jobId, Handle handle, Work work) {
        ScheduledFuture<?> deadline = null;
        // A running generation keeps the desktop app from its idle shutdown.
        try (ActivityTracker.Activity ignored = activity.begin()) {
            if (!jobs.markRunning(jobId, clock.instant())) {
                return;
            }
            deadline = watchdog.schedule(() -> expire(jobId), timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!enterProviderCode(handle)) {
                return;
            }
            Outcome outcome = null;
            Throwable workFailure = null;
            try {
                outcome = work.run();
            } catch (Throwable thrown) {
                workFailure = thrown;
            }
            if (!leaveProviderCode(handle)) {
                return;
            }
            if (workFailure != null) {
                throw workFailure;
            }
            Outcome completed = outcome;
            Boolean published = transactions.execute(status -> {
                if (!jobs.markSucceeded(jobId, completed.resultId(), clock.instant())) {
                    return false;
                }
                completed.persist().run();
                return true;
            });
            if (!Boolean.TRUE.equals(published)) {
                LOG.info("Job {} finished after it was cancelled or expired; result discarded", jobId);
            }
        } catch (Throwable failure) {
            Thread.interrupted();
            JobFailures.Failure classified = JobFailures.classify(failure);
            LOG.warn("Job {} failed: {} ({})", jobId, classified.code(), failure.getClass().getSimpleName());
            jobs.finish(jobId, JobRepository.Status.RUNNING, JobRepository.Status.FAILED,
                    classified.code(), classified.message(), clock.instant());
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            handles.remove(jobId);
        }
    }

    private void expire(String jobId) {
        if (jobs.finish(jobId, JobRepository.Status.RUNNING, JobRepository.Status.FAILED, "timeout",
                "The job exceeded its time limit.", clock.instant())) {
            interrupt(jobId);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        watchdog.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static JobView view(JobRepository.JobRecord job) {
        JobError error = job.errorCode() == null ? null : new JobError(job.errorCode(), job.errorMessage());
        return new JobView(job.id(), job.projectId(), job.type(), job.status(), job.provider(), job.model(),
                job.createdAt(), job.updatedAt(), job.startedAt(), job.finishedAt(), job.resultId(), error);
    }
}
