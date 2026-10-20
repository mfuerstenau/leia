package io.github.mfuerstenau.leia.server.filter;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;

/**
 * {@code startFilter}: checks the params and parses the query or compiles the pattern at once, then
 * finds the matching documents of the {@link CurrentIndex} in a job ({@link GetFilterHandler}, ADR
 * 0031, ADR 0032), on Lucene 10 or a runtime (ADR 0056).
 */
public final class StartFilterHandler implements MethodHandler<StartFilterParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartFilterHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartFilterParams, JobStarted> method() {
        return Methods.START_FILTER;
    }

    /**
     * @throws RpcException {@code INVALID_PARAMS} unless exactly one of the query and the pattern is
     *     present, and the errors of {@code startSearch} for a query and of {@code startStoredScan}
     *     for a pattern
     */
    @Override
    public JobStarted handle(StartFilterParams params) throws RpcException {
        if (params.query().isPresent() == params.pattern().isPresent()) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: the filter needs exactly one of query and pattern");
        }
        return RuntimeResults.startFilter(currentIndex.runtime(), params, jobs);
    }
}
