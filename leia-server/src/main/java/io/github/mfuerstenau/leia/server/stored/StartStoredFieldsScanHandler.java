package io.github.mfuerstenau.leia.server.stored;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.document.StartStoredFieldsScanParams;
import io.github.mfuerstenau.leia.protocol.document.StoredFields;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.OpenRuntime;
import jakarta.inject.Inject;
import java.io.IOException;

/**
 * {@code startStoredFieldsScan}: finds the fields the documents of the {@link CurrentIndex} store
 * values in, in a job ({@link GetStoredFieldsScanHandler}), through the {@link StoredFieldsCache}
 * (ADR 0113).
 */
public final class StartStoredFieldsScanHandler implements MethodHandler<StartStoredFieldsScanParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;
    private final StoredFieldsCache cache;

    @Inject
    StartStoredFieldsScanHandler(CurrentIndex currentIndex, Jobs jobs, StoredFieldsCache cache) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
        this.cache = cache;
    }

    @Override
    public RpcMethod<StartStoredFieldsScanParams, JobStarted> method() {
        return Methods.START_STORED_FIELDS_SCAN;
    }

    @Override
    public JobStarted handle(StartStoredFieldsScanParams params) throws RpcException {
        OpenRuntime open = currentIndex.runtime();
        Documents documents = open.index().documents().orElseThrow(open::notSupported);
        boolean ignoreCache = params.ignoreCache();
        long jobId = jobs.start(
                "stored fields scan" + (ignoreCache ? ", ignoring the cache" : "") + " with Lucene "
                        + open.runtime().luceneVersion(),
                StoredFields.class,
                progress -> {
                    try {
                        return StoredFieldsScan.scan(open.index(), documents, cache, ignoreCache, progress);
                    } catch (IOException e) {
                        throw new RpcException(
                                ErrorCode.INDEX_NOT_READABLE, "Cannot read the stored fields: " + e.getMessage(), e);
                    }
                });
        return new JobStarted(jobId);
    }
}
