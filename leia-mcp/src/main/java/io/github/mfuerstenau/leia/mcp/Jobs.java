package io.github.mfuerstenau.leia.mcp;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import java.io.Serial;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a backend job behind one tool call (ADR 0100 §4): {@code start<Op>}, then {@code get<Op>}
 * at the TUI's cadence until it is done, and {@code cancelJob} if the request is cancelled or the
 * job runs past {@link #LIMIT}. Cancellation stays cooperative (AGENTS.md, hard rule 7).
 */
public final class Jobs {

    private static final Logger LOG = Logger.getLogger(Jobs.class.getName());

    /** How many polls of a new job come at {@link #FIRST_POLL_INTERVAL}: most searches take a few milliseconds. */
    static final int FIRST_POLLS = 10;

    static final Duration FIRST_POLL_INTERVAL = Duration.ofMillis(10);

    static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    /** How long a job may run before it is cancelled and the call fails. */
    public static final Duration LIMIT = Duration.ofMinutes(5);

    /** How far a job is: what it has scanned, of how many if it knows. */
    public record Progress(long scanned, OptionalLong total) {}

    /** The request was cancelled, by the client or by the end of stdin: it gets no answer. */
    public static final class Cancelled extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        public Cancelled() {
            super("cancelled");
        }
    }

    private Jobs() {}

    /**
     * Starts the job and polls it until {@code done} gives its outcome. If the outcome comes from a
     * poll that isn't {@code finished}, the job is still running and is cancelled.
     *
     * @param poll the params of {@code get} for the job
     * @param finished whether a poll holds the job's own outcome
     * @param onProgress gets the progress after each poll that changed it
     * @param cancelled whether the request is cancelled, asked before each poll
     */
    // The parts of one job (ADR 0088)
    @SuppressWarnings("java:S107")
    static <P, Q, G, T> T run(
            Call call,
            RpcMethod<P, JobStarted> start,
            P params,
            RpcMethod<Q, G> get,
            LongFunction<Q> poll,
            Function<G, Optional<T>> done,
            Predicate<G> finished,
            Function<G, Progress> progress,
            Consumer<Progress> onProgress,
            BooleanSupplier cancelled)
            throws ToolException, Cancelled {
        long jobId = call.call(start, params).jobId();
        long deadline = System.nanoTime() + LIMIT.toNanos();
        Progress last = null;
        for (int polls = 0; ; polls++) {
            if (cancelled.getAsBoolean()) {
                cancel(call, jobId);
                throw new Cancelled();
            }
            if (System.nanoTime() - deadline > 0) {
                cancel(call, jobId);
                throw new ToolException("The job took longer than " + LIMIT.toMinutes()
                        + " minutes and was cancelled; narrow it down, such as with fewer fields or segments");
            }
            G result = call.call(get, poll.apply(jobId));
            Optional<T> outcome = done.apply(result);
            if (outcome.isPresent()) {
                if (!finished.test(result)) {
                    cancel(call, jobId);
                }
                return outcome.get();
            }
            Progress now = progress.apply(result);
            if (!now.equals(last)) {
                onProgress.accept(now);
                last = now;
            }
            sleep(polls < FIRST_POLLS ? FIRST_POLL_INTERVAL : POLL_INTERVAL, call, jobId);
        }
    }

    private static void cancel(Call call, long jobId) {
        try {
            call.call(Methods.CANCEL_JOB, new JobParams(jobId));
        } catch (ToolException | Cancelled e) {
            LOG.log(Level.FINE, e, () -> "Cannot cancel job " + jobId);
        }
    }

    private static void sleep(Duration interval, Call call, long jobId) throws Cancelled {
        try {
            Thread.sleep(interval);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            cancel(call, jobId);
            throw new Cancelled();
        }
    }
}
