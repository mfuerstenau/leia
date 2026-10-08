package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.core.InvalidQueryException;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.core.ScanProgress;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.HitField;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.SortValue;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.TotalHitsRelation;
import io.github.mfuerstenau.leia.server.ByteStrings;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.document.GetDocumentHandler;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.similarities.Similarity;

/**
 * {@code startSearch}: checks the params and parses the query at once, then searches the {@link
 * CurrentIndex} in a job ({@link GetSearchHandler}).
 */
public final class StartSearchHandler implements MethodHandler<StartSearchParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartSearchHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartSearchParams, JobStarted> method() {
        return Methods.START_SEARCH;
    }

    @Override
    public JobStarted handle(StartSearchParams params) throws RpcException {
        int offset = params.offset();
        int limit = params.limit();
        if (limit < 1 || limit > StartSearchParams.MAX_LIMIT) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: limit must be between 1 and " + StartSearchParams.MAX_LIMIT + ", not " + limit);
        }
        if (offset < 0 || offset > StartSearchParams.MAX_WINDOW - limit) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: offset must be between 0 and " + (StartSearchParams.MAX_WINDOW - limit)
                            + " for a limit of " + limit + ", not " + offset);
        }
        ReadOnlyIndex index = currentIndex.readable();
        Optional<Set<String>> fields = params.fields().map(Set::copyOf);
        if (fields.isPresent()) {
            for (String field : fields.get()) {
                SearchQueries.checkField(index, field);
            }
        }
        Similarity similarity = SearchQueries.similarity(params.similarity());
        Optional<Sort> sort = SearchQueries.sort(index, params.sort());
        boolean exact = params.exactTotalHits();
        // Last: from here on, the job owns the query's analyzer and closes it.
        SearchQueries.AnalyzedQuery analyzed = SearchQueries.query(index, params.query());
        Query query = analyzed.query();
        long jobId = jobs.start(
                "search " + query + sort.map(s -> " sorted by " + s).orElse("") + " with " + similarity + ", hits "
                        + offset + " to " + (offset + limit),
                SearchResults.class,
                progress -> {
                    try (analyzed) {
                        return search(index, query, similarity, sort, offset, limit, exact, fields, progress);
                    }
                });
        return new JobStarted(jobId);
    }

    private static SearchResults search(
            ReadOnlyIndex index,
            Query query,
            Similarity similarity,
            Optional<Sort> sort,
            int offset,
            int limit,
            boolean exact,
            Optional<Set<String>> fields,
            ScanProgress progress)
            throws RpcException {
        try {
            return toResults(index.search(query, similarity, sort, offset, limit, exact, fields, progress));
        } catch (InvalidQueryException e) {
            throw SearchQueries.invalidQuery(e);
        } catch (IOException e) {
            throw new RpcException(ErrorCode.INDEX_NOT_READABLE, "Cannot search the index: " + e.getMessage(), e);
        }
    }

    private static SearchResults toResults(io.github.mfuerstenau.leia.core.SearchResults results) {
        return new SearchResults(
                results.query(),
                results.rewrittenQuery(),
                results.totalHits().value(),
                switch (results.totalHits().relation()) {
                    case EQUAL_TO -> TotalHitsRelation.EQUAL_TO;
                    case GREATER_THAN_OR_EQUAL_TO -> TotalHitsRelation.GREATER_THAN_OR_EQUAL_TO;
                },
                results.hits().stream().map(StartSearchHandler::toHit).toList(),
                results.took().toMillis());
    }

    private static Hit toHit(io.github.mfuerstenau.leia.core.SearchResults.Hit hit) {
        List<HitField> fields = hit.fields().stream()
                .map(field -> new HitField(
                        field.name(),
                        field.values().stream()
                                .map(value -> GetDocumentHandler.storedValue(value, SearchResults.MAX_VALUE_LENGTH))
                                .toList()))
                .toList();
        List<SortValue> sortValues = hit.sortValues().stream()
                .map(value -> new SortValue(
                        value.number().map(Number::toString), value.term().map(ByteStrings::bytesValue)))
                .toList();
        return new Hit(hit.docId(), hit.score(), sortValues, fields);
    }
}
