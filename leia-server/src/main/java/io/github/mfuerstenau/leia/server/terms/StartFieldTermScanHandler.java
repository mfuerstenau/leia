package io.github.mfuerstenau.leia.server.terms;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.terms.StartFieldTermScanParams;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;

/**
 * {@code startFieldTermScan}: checks the params at once, then counts the terms of a field of the
 * {@link CurrentIndex} and finds its top terms in a job ({@link GetFieldTermScanHandler}).
 */
public final class StartFieldTermScanHandler implements MethodHandler<StartFieldTermScanParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartFieldTermScanHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartFieldTermScanParams, JobStarted> method() {
        return Methods.START_FIELD_TERM_SCAN;
    }

    @Override
    public JobStarted handle(StartFieldTermScanParams params) throws RpcException {
        int topN = params.topN();
        if (topN < 0 || topN > StartFieldTermScanParams.MAX_TOP_N) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: topN must be between 0 and " + StartFieldTermScanParams.MAX_TOP_N + ", not "
                            + topN);
        }
        return RuntimeResults.startFieldTermScan(currentIndex.runtime(), params.field(), topN, jobs);
    }
}
