package io.github.mfuerstenau.leia.server.terms;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.terms.ExportTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetExportTermsResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;

/** {@code getExportTerms}: the progress of a job of {@link StartExportTermsHandler}, then its outcome. */
public final class GetExportTermsHandler implements MethodHandler<JobParams, GetExportTermsResult> {

    private final Jobs jobs;

    @Inject
    GetExportTermsHandler(Jobs jobs) {
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<JobParams, GetExportTermsResult> method() {
        return Methods.GET_EXPORT_TERMS;
    }

    @Override
    public GetExportTermsResult handle(JobParams params) throws RpcException {
        Jobs.Poll<ExportTermsResult> poll = jobs.poll(params.jobId(), ExportTermsResult.class);
        return new GetExportTermsResult(poll.progress(), poll.result());
    }
}
