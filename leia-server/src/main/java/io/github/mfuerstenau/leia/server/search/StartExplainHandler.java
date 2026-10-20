package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;

/**
 * {@code startExplain}: checks the params and parses the query at once, then explains a document of
 * the {@link CurrentIndex} in a job ({@link GetExplainHandler}), on Lucene 10 or a runtime (ADR 0057).
 */
public final class StartExplainHandler implements MethodHandler<StartExplainParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartExplainHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartExplainParams, JobStarted> method() {
        return Methods.START_EXPLAIN;
    }

    @Override
    public JobStarted handle(StartExplainParams params) throws RpcException {
        return RuntimeResults.startExplain(currentIndex.runtime(), params, jobs);
    }
}
