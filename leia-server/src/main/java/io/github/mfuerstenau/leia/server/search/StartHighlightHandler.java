package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.search.StartHighlightParams;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.RuntimeHighlights;
import jakarta.inject.Inject;

/**
 * {@code startHighlight}: checks the params and parses the query at once, then finds the query's
 * matches in one field, or all stored fields, of a document of the {@link CurrentIndex} in a job
 * ({@link GetHighlightHandler}, ADR 0042, ADR 0043), or of an index a runtime opened ({@link
 * RuntimeHighlights}, ADR 0063).
 */
public final class StartHighlightHandler implements MethodHandler<StartHighlightParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartHighlightHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartHighlightParams, JobStarted> method() {
        return Methods.START_HIGHLIGHT;
    }

    @Override
    public JobStarted handle(StartHighlightParams params) throws RpcException {
        return RuntimeHighlights.startHighlight(currentIndex.runtime(), params, jobs);
    }
}
