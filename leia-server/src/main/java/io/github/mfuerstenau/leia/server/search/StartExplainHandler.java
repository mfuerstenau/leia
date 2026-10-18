package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.core.InvalidQueryException;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.core.ScanProgress;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.search.Explanation;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.document.GetDocumentHandler;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Arrays;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.similarities.Similarity;

/**
 * {@code startExplain}: checks the params and parses the query at once, then explains a document of
 * the {@link CurrentIndex} in a job ({@link GetExplainHandler}), on Lucene 10 or a runtime (ADR 0057).
 */
public final class StartExplainHandler implements MethodHandler<StartExplainParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartExplainHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartExplainParams, JobStarted> method() {
        return Methods.START_EXPLAIN;
    }

    @Override
    public JobStarted handle(StartExplainParams params) throws RpcException {
        if (currentIndex.runtime().isPresent()) {
            return RuntimeResults.startExplain(currentIndex.runtime().get(), params, jobs);
        }
        ReadOnlyIndex index = currentIndex.readable();
        int docId = params.docId();
        GetDocumentHandler.checkDocId(index, docId);
        Similarity similarity = SearchQueries.similarity(params.similarity());
        // Last: from here on, the job owns the query's analyzer and closes it.
        SearchQueries.AnalyzedQuery analyzed = SearchQueries.query(index, params.query());
        Query query = analyzed.query();
        long jobId = jobs.start(
                "explain " + query + " with " + similarity + " for document " + docId, Explanation.class, progress -> {
                    try (analyzed) {
                        return explain(index, query, similarity, docId, progress);
                    }
                });
        return new JobStarted(jobId);
    }

    private static Explanation explain(
            ReadOnlyIndex index, Query query, Similarity similarity, int docId, ScanProgress progress)
            throws RpcException {
        try {
            return toExplanation(index.explain(query, similarity, docId, progress));
        } catch (InvalidQueryException e) {
            throw SearchQueries.invalidQuery(e);
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE, "Cannot explain document " + docId + ": " + e.getMessage(), e);
        }
    }

    static Explanation toExplanation(org.apache.lucene.search.Explanation explanation) {
        return new Explanation(
                explanation.isMatch(),
                explanation.getValue().toString(),
                explanation.getDescription(),
                Arrays.stream(explanation.getDetails())
                        .map(StartExplainHandler::toExplanation)
                        .toList());
    }
}
