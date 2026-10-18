package io.github.mfuerstenau.leia.server.filter;

import io.github.mfuerstenau.leia.core.DocFilter;
import io.github.mfuerstenau.leia.core.InvalidQueryException;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.filter.FilterPattern;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import io.github.mfuerstenau.leia.server.search.SearchQueries;
import io.github.mfuerstenau.leia.server.search.StartStoredScanHandler;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code startFilter}: checks the params and parses the query or compiles the pattern at once, then
 * finds the matching documents of the {@link CurrentIndex} in a job ({@link GetFilterHandler}, ADR
 * 0031, ADR 0032), on Lucene 10 or a runtime (ADR 0056).
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

    /**
     * @throws RpcException {@code INVALID_PARAMS} unless exactly one of the query and the pattern is
     *     present, and the errors of {@code startSearch} for a query and of {@code startStoredScan}
     *     for a pattern
     */
    @Override
    public JobStarted handle(StartFilterParams params) throws RpcException {
        if (params.query().isPresent() == params.pattern().isPresent()) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: the filter needs exactly one of query and pattern");
        }
        if (currentIndex.runtime().isPresent()) {
            return RuntimeResults.startFilter(currentIndex.runtime().get(), params, jobs);
        }
        ReadOnlyIndex index = currentIndex.readable();
        boolean includeDeleted = params.includeDeleted();
        long jobId = params.query().isPresent()
                ? startQuery(index, params.query().get(), includeDeleted)
                : startPattern(index, params.pattern().orElseThrow(), includeDeleted);
        return new JobStarted(jobId);
    }

    private long startQuery(ReadOnlyIndex index, SearchQuery query, boolean includeDeleted) throws RpcException {
        // Last: from here on, the job owns the query's analyzer and closes it.
        SearchQueries.AnalyzedQuery analyzed = SearchQueries.query(index, query);
        return jobs.start("filter by " + analyzed.query() + deleted(includeDeleted), DocFilter.class, progress -> {
            try (analyzed) {
                return index.matches(analyzed.query(), includeDeleted, progress);
            } catch (InvalidQueryException e) {
                throw SearchQueries.invalidQuery(e);
            } catch (IOException e) {
                throw new RpcException(ErrorCode.INDEX_NOT_READABLE, "Cannot search the index: " + e.getMessage(), e);
            }
        });
    }

    private long startPattern(ReadOnlyIndex index, FilterPattern filterPattern, boolean includeDeleted)
            throws RpcException {
        Optional<Set<String>> fields = filterPattern.fields().map(Set::copyOf);
        if (fields.isPresent()) {
            for (String field : fields.get()) {
                SearchQueries.checkField(index, field);
            }
        }
        Pattern pattern = StartStoredScanHandler.pattern(filterPattern.pattern(), filterPattern.caseInsensitive());
        return jobs.start(
                "filter by /" + pattern + "/" + (filterPattern.caseInsensitive() ? "i" : "")
                        + fields.map(names -> " in " + names).orElse("") + deleted(includeDeleted),
                DocFilter.class,
                progress -> {
                    try {
                        return index.storedScanMatches(
                                pattern, fields, includeDeleted, StartStoredScanHandler.VALUE_TIME_LIMIT, progress);
                    } catch (IOException e) {
                        throw new RpcException(
                                ErrorCode.INDEX_NOT_READABLE, "Cannot read the stored values: " + e.getMessage(), e);
                    }
                });
    }

    private static String deleted(boolean includeDeleted) {
        return includeDeleted ? ", deleted documents too" : "";
    }
}
