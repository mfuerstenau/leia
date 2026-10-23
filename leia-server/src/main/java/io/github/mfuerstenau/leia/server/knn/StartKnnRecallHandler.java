package io.github.mfuerstenau.leia.server.knn;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.knn.StartKnnRecallParams;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.RuntimeKnnRecall;
import jakarta.inject.Inject;

/** {@code startKnnRecall}: the recall of approximate KNN search in the {@link CurrentIndex}, as a job (ADR 0086). */
public final class StartKnnRecallHandler implements MethodHandler<StartKnnRecallParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartKnnRecallHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartKnnRecallParams, JobStarted> method() {
        return Methods.START_KNN_RECALL;
    }

    @Override
    public JobStarted handle(StartKnnRecallParams params) throws RpcException {
        return RuntimeKnnRecall.start(currentIndex.runtime(), params, jobs);
    }
}
