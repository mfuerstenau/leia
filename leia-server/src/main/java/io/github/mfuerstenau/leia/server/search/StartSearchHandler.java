package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;

/**
 * {@code startSearch}: checks the params and parses the query at once, then searches the {@link
 * CurrentIndex} in a job ({@link GetSearchHandler}).
 */
public final class StartSearchHandler implements MethodHandler<StartSearchParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartSearchHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartSearchParams, JobStarted> method() {
        return Methods.START_SEARCH;
    }

    @Override
    public JobStarted handle(StartSearchParams params) throws RpcException {
        return RuntimeResults.startSearch(currentIndex.runtime(), params, jobs);
    }
}
