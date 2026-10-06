package io.github.mfuerstenau.leia.server.job;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.CancelJobResult;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.server.MethodHandler;
import jakarta.inject.Inject;

/** {@code cancelJob}: stops a job of any kind. Idempotent, like {@code closeIndex}. */
public final class CancelJobHandler implements MethodHandler<JobParams, CancelJobResult> {

    private final Jobs jobs;

    @Inject
    CancelJobHandler(Jobs jobs) {
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<JobParams, CancelJobResult> method() {
        return Methods.CANCEL_JOB;
    }

    @Override
    public CancelJobResult handle(JobParams params) {
        return new CancelJobResult(jobs.cancel(params.jobId()));
    }
}
