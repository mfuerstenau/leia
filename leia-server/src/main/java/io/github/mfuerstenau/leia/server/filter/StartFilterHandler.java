package io.github.mfuerstenau.leia.server.filter;

import io.github.mfuerstenau.leia.core.DocFilter;
import io.github.mfuerstenau.leia.core.InvalidQueryException;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.search.SearchQueries;
import jakarta.inject.Inject;
import java.io.IOException;

/**
 * {@code startFilter}: checks the params and parses the query at once, then finds the matching
 * documents of the {@link CurrentIndex} in a job ({@link GetFilterHandler}, ADR 0031).
 */
public final class StartFilterHandler implements MethodHandler<StartFilterParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartFilterHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartFilterParams, JobStarted> method() {
        return Methods.START_FILTER;
    }

    @Override
    public JobStarted handle(StartFilterParams params) throws RpcException {
        ReadOnlyIndex index = currentIndex.readable();
        boolean includeDeleted = params.includeDeleted();
        // Last: from here on, the job owns the query's analyzer and closes it.
        SearchQueries.AnalyzedQuery analyzed = SearchQueries.query(index, params.query());
        long jobId = jobs.start(
                "filter by " + analyzed.query() + (includeDeleted ? ", deleted documents too" : ""),
                DocFilter.class,
                progress -> {
                    try (analyzed) {
                        return index.matches(analyzed.query(), includeDeleted, progress);
                    } catch (InvalidQueryException e) {
                        throw SearchQueries.invalidQuery(e);
                    } catch (IOException e) {
                        throw new RpcException(
                                ErrorCode.INDEX_NOT_READABLE, "Cannot search the index: " + e.getMessage(), e);
                    }
                });
        return new JobStarted(jobId);
    }
}
