package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.search.DocumentHighlight;
import io.github.mfuerstenau.leia.protocol.search.GetHighlightResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;

/** {@code getHighlight}: the outcome of a job of {@link StartHighlightHandler} once it is done. */
public final class GetHighlightHandler implements MethodHandler<JobParams, GetHighlightResult> {

    private final Jobs jobs;

    @Inject
    GetHighlightHandler(Jobs jobs) {
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<JobParams, GetHighlightResult> method() {
        return Methods.GET_HIGHLIGHT;
    }

    @Override
    public GetHighlightResult handle(JobParams params) throws RpcException {
        return new GetHighlightResult(
                jobs.poll(params.jobId(), DocumentHighlight.class).result());
    }
}
