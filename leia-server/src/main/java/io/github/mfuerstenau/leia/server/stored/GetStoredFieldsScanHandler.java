package io.github.mfuerstenau.leia.server.stored;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.document.GetStoredFieldsScanResult;
import io.github.mfuerstenau.leia.protocol.document.StoredFields;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;

/** {@code getStoredFieldsScan}: the progress of a job of {@link StartStoredFieldsScanHandler}, then its outcome. */
public final class GetStoredFieldsScanHandler implements MethodHandler<JobParams, GetStoredFieldsScanResult> {

    private final Jobs jobs;

    @Inject
    GetStoredFieldsScanHandler(Jobs jobs) {
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<JobParams, GetStoredFieldsScanResult> method() {
        return Methods.GET_STORED_FIELDS_SCAN;
    }

    @Override
    public GetStoredFieldsScanResult handle(JobParams params) throws RpcException {
        Jobs.Poll<StoredFields> poll = jobs.poll(params.jobId(), StoredFields.class);
        return new GetStoredFieldsScanResult(poll.progress(), poll.result());
    }
}
