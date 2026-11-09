package io.github.mfuerstenau.leia.server.terms;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.terms.FieldTerms;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;

/** {@code getFieldTermScan}: the progress of a job of {@link StartFieldTermScanHandler}, then its outcome. */
public final class GetFieldTermScanHandler implements MethodHandler<JobParams, GetFieldTermScanResult> {

    private final Jobs jobs;

    @Inject
    GetFieldTermScanHandler(Jobs jobs) {
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<JobParams, GetFieldTermScanResult> method() {
        return Methods.GET_FIELD_TERM_SCAN;
    }

    @Override
    public GetFieldTermScanResult handle(JobParams params) throws RpcException {
        Jobs.Poll<FieldTerms> poll = jobs.poll(params.jobId(), FieldTerms.class);
        return new GetFieldTermScanResult(poll.progress(), poll.outcome());
    }
}
