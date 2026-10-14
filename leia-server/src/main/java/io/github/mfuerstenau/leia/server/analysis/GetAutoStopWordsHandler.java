package io.github.mfuerstenau.leia.server.analysis;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.analysis.AutoStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.GetAutoStopWordsResult;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;

/** {@code getAutoStopWords}: the progress of a job of {@link StartAutoStopWordsHandler}, then its outcome. */
public final class GetAutoStopWordsHandler implements MethodHandler<JobParams, GetAutoStopWordsResult> {

    private final Jobs jobs;

    @Inject
    GetAutoStopWordsHandler(Jobs jobs) {
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<JobParams, GetAutoStopWordsResult> method() {
        return Methods.GET_AUTO_STOP_WORDS;
    }

    @Override
    public GetAutoStopWordsResult handle(JobParams params) throws RpcException {
        Jobs.Poll<AutoStopWords> poll = jobs.poll(params.jobId(), AutoStopWords.class);
        return new GetAutoStopWordsResult(poll.progress(), poll.result());
    }
}
