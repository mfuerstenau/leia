package io.github.mfuerstenau.leia.server.filter;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.filter.FilterResult;
import io.github.mfuerstenau.leia.protocol.filter.GetFilterResult;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;

/**
 * {@code getFilter}: the progress of a job of {@link StartFilterHandler}, then the filter it built,
 * which {@link Filters} keeps under the job's id from then on.
 */
public final class GetFilterHandler implements MethodHandler<JobParams, GetFilterResult> {

    private final Jobs jobs;
    private final Filters filters;

    @Inject
    GetFilterHandler(Jobs jobs, Filters filters) {
        this.jobs = jobs;
        this.filters = filters;
    }

    @Override
    public RpcMethod<JobParams, GetFilterResult> method() {
        return Methods.GET_FILTER;
    }

    @Override
    public GetFilterResult handle(JobParams params) throws RpcException {
        long filterId = params.jobId();
        Jobs.Poll<DocFilter> poll = jobs.poll(filterId, DocFilter.class);
        poll.result().ifPresent(filter -> filters.put(filterId, filter));
        return new GetFilterResult(
                poll.progress(),
                poll.result()
                        .map(filter -> new FilterResult(
                                filterId,
                                filter.matchCount(),
                                filter.query(),
                                filter.rewrittenQuery(),
                                filter.took().toMillis(),
                                filter.timedOutValues())));
    }
}
