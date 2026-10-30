package io.github.mfuerstenau.leia.mcp;

import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Predicate;

/** What a {@link McpTool} calls the backend through, for one request (ADR 0100 §4). */
public interface Call {

    /** What stays between the requests: the open index. */
    Session session();

    /**
     * Calls {@code method} through {@code LeiaBackend.call} and waits for its result.
     *
     * @throws ToolException for an expected failure of the backend
     */
    <P, R> R call(RpcMethod<P, R> method, P params) throws ToolException, Jobs.Cancelled;

    /**
     * Starts a job and polls it until {@code done} gives its outcome, reporting {@code progress} to
     * the client if it asked for it.
     *
     * @throws ToolException for an expected failure, or if the job takes longer than {@link
     *     Jobs#LIMIT}; the job is cancelled then
     * @throws Jobs.Cancelled if the request was cancelled; so is the job
     */
    default <P, G, T> T job(
            RpcMethod<P, JobStarted> start,
            P params,
            RpcMethod<JobParams, G> get,
            Function<G, Optional<T>> done,
            Function<G, Jobs.Progress> progress)
            throws ToolException, Jobs.Cancelled {
        return job(start, params, get, JobParams::new, done, _ -> true, progress);
    }

    /**
     * Like {@link #job(RpcMethod, Object, RpcMethod, Function, Function)}, for a {@code get<Op>}
     * with params of its own, and a {@code done} that may give its outcome before the job is
     * finished: the job is cancelled then.
     *
     * @param poll the params of {@code get} for the job
     * @param finished whether a poll holds the job's own outcome
     */
    // The parts of one job (ADR 0088)
    @SuppressWarnings("java:S107")
    <P, Q, G, T> T job(
            RpcMethod<P, JobStarted> start,
            P params,
            RpcMethod<Q, G> get,
            LongFunction<Q> poll,
            Function<G, Optional<T>> done,
            Predicate<G> finished,
            Function<G, Jobs.Progress> progress)
            throws ToolException, Jobs.Cancelled;
}
