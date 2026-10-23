package io.github.mfuerstenau.leia.server.job;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.server.RpcException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Long-running operations, each on its own daemon thread (ADR 0005). A job reports progress through
 * a {@link ScanProgress}, which also stops it once it is cancelled: cancellation is cooperative,
 * threads are never interrupted.
 *
 * <p>The request loop, or the backend thread of {@code LeiaBackend}, is the only caller, so the
 * registry needs no locking; a job's thread shares
 * only the job's progress, cancelled flag and outcome with it.
 */
@Singleton
public final class Jobs {

    private static final Logger LOG = Logger.getLogger(Jobs.class.getName());

    /** How long cancelling waits for a job to stop. */
    static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofPlatform().daemon().name("leia-job-", 1).factory());
    private final Map<Long, Job<?>> byId = new HashMap<>();
    private long nextId = 1;

    @Inject
    public Jobs() {
        // For Dagger.
    }

    /** The work of a job. It runs on the job's thread and must pass {@code progress} its progress. */
    @FunctionalInterface
    public interface Work<R> {
        /**
         * @throws RpcException for an expected failure, which the next poll answers with
         * @throws CancellationException from {@code progress} once the job is cancelled
         */
        R run(ScanProgress progress) throws RpcException;
    }

    /**
     * A job's state, as a poll sees it.
     *
     * @param progress how far the job has got, such as terms scanned
     * @param result the outcome; empty while the job runs
     */
    public record Poll<R>(long progress, Optional<R> result) {}

    /**
     * Starts {@code work} on a new thread.
     *
     * @param description what the job does, for the log
     * @param resultType the type of the outcome, which polls must ask for
     * @return the job's id
     */
    public <R> long start(String description, Class<R> resultType, Work<R> work) {
        return start(description, resultType, Optional.empty(), work);
    }

    /**
     * Starts {@code work} on a new thread, with an object that the work writes and polls read, such
     * as the lines of output of a check (ADR 0022). It must be safe to use from both threads.
     *
     * @param description what the job does, for the log
     * @param resultType the type of the outcome, which polls must ask for
     * @param attachment what {@link #attachment} returns
     * @return the job's id
     */
    public <R> long start(String description, Class<R> resultType, Object attachment, Work<R> work) {
        return start(description, resultType, Optional.of(attachment), work);
    }

    private <R> long start(String description, Class<R> resultType, Optional<Object> attachment, Work<R> work) {
        long id = nextId++;
        Job<R> job = new Job<>(id, description, resultType, attachment);
        byId.put(id, job);
        executor.execute(() -> job.run(work));
        LOG.fine(() -> "Started job " + id + ": " + description);
        return id;
    }

    /**
     * The progress of a job, and its outcome once it is done. The outcome is handed out once: after
     * that, the job is forgotten.
     *
     * @throws RpcException {@code JOB_NOT_FOUND} if there is no such job with this result type, or the
     *     job's own error if it failed
     */
    public <R> Poll<R> poll(long id, Class<R> resultType) throws RpcException {
        Job<?> job = byId.get(id);
        if (job == null || job.resultType != resultType) {
            throw notFound(id);
        }
        if (!job.outcome.isDone()) {
            return new Poll<>(job.progress, Optional.empty());
        }
        byId.remove(id);
        try {
            return new Poll<>(job.progress, Optional.of(resultType.cast(job.outcome.join())));
        } catch (CompletionException e) {
            if (e.getCause() instanceof RpcException failure) {
                throw failure;
            }
            throw new RpcException(ErrorCode.INTERNAL_ERROR, "Internal error: " + e.getCause(), e.getCause());
        }
    }

    /**
     * The object a job was started with. Take it before {@link #poll}, which forgets a job that is
     * done.
     *
     * @throws RpcException {@code JOB_NOT_FOUND} if there is no such job with this result type, or it
     *     has no attachment of this type
     */
    public <A> A attachment(long id, Class<?> resultType, Class<A> type) throws RpcException {
        Job<?> job = byId.get(id);
        if (job == null || job.resultType != resultType) {
            throw notFound(id);
        }
        return job.attachment.filter(type::isInstance).map(type::cast).orElseThrow(() -> notFound(id));
    }

    /**
     * Stops a job, waits until it has stopped, and forgets it.
     *
     * @return whether the job was still running; false if it had finished or is unknown
     */
    public boolean cancel(long id) {
        Job<?> job = byId.remove(id);
        if (job == null) {
            return false;
        }
        boolean running = !job.outcome.isDone();
        job.cancelled = true;
        awaitStop(job);
        if (running) {
            LOG.info(() -> "Cancelled job " + id + ": " + job.description);
        }
        return running;
    }

    /** Stops every job and waits for each, so that the index they read can be closed. */
    public void cancelAll() {
        for (long id : List.copyOf(byId.keySet())) {
            cancel(id);
        }
    }

    /** Whether the job has an outcome that was not handed out yet. */
    boolean isDone(long id) {
        Job<?> job = byId.get(id);
        return job != null && job.outcome.isDone();
    }

    private static void awaitStop(Job<?> job) {
        try {
            job.outcome.get(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | CancellationException _) {
            // Stopped.
        } catch (TimeoutException _) {
            LOG.warning(() ->
                    "Job " + job.id + " did not stop within " + STOP_TIMEOUT.toSeconds() + " s: " + job.description);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static RpcException notFound(long id) {
        return new RpcException(ErrorCode.JOB_NOT_FOUND, "No job " + id + ": unknown, cancelled or already finished");
    }

    private static final class Job<R> {

        final long id;
        final String description;
        final Class<R> resultType;
        final Optional<Object> attachment;
        /** Completed by the job's thread only, when the work has returned or failed. */
        final CompletableFuture<R> outcome = new CompletableFuture<>();

        volatile long progress;
        volatile boolean cancelled;

        Job(long id, String description, Class<R> resultType, Optional<Object> attachment) {
            this.id = id;
            this.description = description;
            this.resultType = resultType;
            this.attachment = attachment;
        }

        void run(Work<R> work) {
            long start = System.nanoTime();
            try {
                outcome.complete(work.run(scanned -> {
                    if (cancelled) {
                        throw new CancellationException("Job " + id + " cancelled");
                    }
                    progress = scanned;
                }));
                LOG.info(() -> "Finished job " + id + " in "
                        + Duration.ofNanos(System.nanoTime() - start).toMillis() + " ms: " + description);
            } catch (RpcException e) {
                outcome.completeExceptionally(e);
            } catch (CancellationException _) {
                outcome.cancel(false);
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, e, () -> "Job " + id + " failed: " + description);
                outcome.completeExceptionally(e);
            } finally {
                // An Error: the job must still end, or polls would wait forever.
                if (!outcome.isDone()) {
                    outcome.completeExceptionally(new IllegalStateException("Job " + id + " ended abnormally"));
                }
            }
        }
    }
}
