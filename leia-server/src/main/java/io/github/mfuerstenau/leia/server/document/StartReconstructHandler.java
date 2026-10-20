package io.github.mfuerstenau.leia.server.document;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.document.StartReconstructParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.util.OptionalLong;

/**
 * {@code startReconstruct}: checks the document and fields at once, counts the terms and points to
 * go through, then rebuilds the terms and points the document was indexed with in a job ({@link
 * GetReconstructHandler}, ADR 0029, ADR 0041).
 */
public final class StartReconstructHandler implements MethodHandler<StartReconstructParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartReconstructHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    /** What a poll of the job needs besides its progress. */
    public record Count(OptionalLong total) {}

    @Override
    public RpcMethod<StartReconstructParams, JobStarted> method() {
        return Methods.START_RECONSTRUCT;
    }

    @Override
    public JobStarted handle(StartReconstructParams params) throws RpcException {
        return RuntimeResults.startReconstruct(currentIndex.runtime(), params, jobs);
    }
}
