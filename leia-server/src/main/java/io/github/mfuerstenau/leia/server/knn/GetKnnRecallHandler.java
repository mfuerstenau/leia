package io.github.mfuerstenau.leia.server.knn;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnRecallResult;
import io.github.mfuerstenau.leia.protocol.knn.KnnRecallResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;

/** {@code getKnnRecall}: the progress of a recall check, and its outcome once it is done (ADR 0086). */
public final class GetKnnRecallHandler implements MethodHandler<JobParams, GetKnnRecallResult> {

    private final Jobs jobs;

    @Inject
    GetKnnRecallHandler(Jobs jobs) {
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<JobParams, GetKnnRecallResult> method() {
        return Methods.GET_KNN_RECALL;
    }

    @Override
    public GetKnnRecallResult handle(JobParams params) throws RpcException {
        Jobs.Poll<KnnRecallResult> poll = jobs.poll(params.jobId(), KnnRecallResult.class);
        return new GetKnnRecallResult(poll.progress(), poll.result());
    }
}
