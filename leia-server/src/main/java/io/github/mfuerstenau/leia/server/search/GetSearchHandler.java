package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.search.GetSearchResult;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;

/** {@code getSearch}: the progress of a job of {@link StartSearchHandler}, then its outcome. */
public final class GetSearchHandler implements MethodHandler<JobParams, GetSearchResult> {

    private final Jobs jobs;

    @Inject
    GetSearchHandler(Jobs jobs) {
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<JobParams, GetSearchResult> method() {
        return Methods.GET_SEARCH;
    }

    @Override
    public GetSearchResult handle(JobParams params) throws RpcException {
        Jobs.Poll<SearchResults> poll = jobs.poll(params.jobId(), SearchResults.class);
        return new GetSearchResult(poll.progress(), poll.outcome());
    }
}
