package io.github.mfuerstenau.leia.server.filter;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import io.github.mfuerstenau.leia.server.stored.StoredFieldsCache;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@code startFilter}: checks the params and parses the query or compiles the pattern at once, then
 * finds the matching documents of the {@link CurrentIndex} in a job ({@link GetFilterHandler}, ADR
 * 0031, ADR 0032), on Lucene 10 or a runtime (ADR 0056), or those that store a field, through the
 * {@link StoredFieldsCache} (ADR 0114).
 */
public final class StartFilterHandler implements MethodHandler<StartFilterParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;
    private final StoredFieldsCache cache;

    @Inject
    StartFilterHandler(CurrentIndex currentIndex, Jobs jobs, StoredFieldsCache cache) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
        this.cache = cache;
    }

    @Override
    public RpcMethod<StartFilterParams, JobStarted> method() {
        return Methods.START_FILTER;
    }

    /**
     * @throws RpcException {@code INVALID_PARAMS} unless exactly one of the query, the pattern and the
     *     stored field is present, the errors of {@code startSearch} for a query and of {@code
     *     startStoredScan} for a pattern, and {@code FIELD_NOT_FOUND} for a stored field the index
     *     doesn't have
     */
    @Override
    public JobStarted handle(StartFilterParams params) throws RpcException {
        long sources = Stream.of(params.query(), params.pattern(), params.storedField())
                .filter(Optional::isPresent)
                .count();
        if (sources != 1) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: the filter needs exactly one of query, pattern and storedField");
        }
        return RuntimeResults.startFilter(currentIndex.runtime(), cache, params, jobs);
    }
}
