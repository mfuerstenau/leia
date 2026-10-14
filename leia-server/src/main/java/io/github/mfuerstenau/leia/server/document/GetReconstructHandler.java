package io.github.mfuerstenau.leia.server.document;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructResult;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;

/** {@code getReconstruct}: the progress of a job of {@link StartReconstructHandler}, then its outcome. */
public final class GetReconstructHandler implements MethodHandler<JobParams, GetReconstructResult> {

    private final Jobs jobs;

    @Inject
    GetReconstructHandler(Jobs jobs) {
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<JobParams, GetReconstructResult> method() {
        return Methods.GET_RECONSTRUCT;
    }

    @Override
    public GetReconstructResult handle(JobParams params) throws RpcException {
        // Read first: the poll that hands out the outcome forgets the job.
        StartReconstructHandler.Count count =
                jobs.attachment(params.jobId(), ReconstructResult.class, StartReconstructHandler.Count.class);
        Jobs.Poll<ReconstructResult> poll = jobs.poll(params.jobId(), ReconstructResult.class);
        return new GetReconstructResult(poll.progress(), count.total(), poll.result());
    }
}
