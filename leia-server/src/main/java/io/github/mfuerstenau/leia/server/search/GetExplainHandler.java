package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.search.Explanation;
import io.github.mfuerstenau.leia.protocol.search.GetExplainResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;

/** {@code getExplain}: the outcome of a job of {@link StartExplainHandler} once it is done. */
public final class GetExplainHandler implements MethodHandler<JobParams, GetExplainResult> {

    private final Jobs jobs;

    @Inject
    GetExplainHandler(Jobs jobs) {
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<JobParams, GetExplainResult> method() {
        return Methods.GET_EXPLAIN;
    }

    @Override
    public GetExplainResult handle(JobParams params) throws RpcException {
        return new GetExplainResult(jobs.poll(params.jobId(), Explanation.class).outcome());
    }
}
