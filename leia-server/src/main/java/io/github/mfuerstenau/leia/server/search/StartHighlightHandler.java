package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.core.HighlightSpec;
import io.github.mfuerstenau.leia.core.InvalidQueryException;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.core.ScanProgress;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.search.DocumentHighlight;
import io.github.mfuerstenau.leia.protocol.search.StartHighlightParams;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.document.GetDocumentHandler;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.RuntimeHighlights;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.Query;

/**
 * {@code startHighlight}: checks the params and parses the query at once, then finds the query's
 * matches in one field, or all stored fields, of a document of the {@link CurrentIndex} in a job
 * ({@link GetHighlightHandler}, ADR 0042, ADR 0043), or of an index a runtime opened ({@link
 * RuntimeHighlights}, ADR 0063).
 */
public final class StartHighlightHandler implements MethodHandler<StartHighlightParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartHighlightHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartHighlightParams, JobStarted> method() {
        return Methods.START_HIGHLIGHT;
    }

    @Override
    public JobStarted handle(StartHighlightParams params) throws RpcException {
        if (currentIndex.runtime().isPresent()) {
            return RuntimeHighlights.startHighlight(currentIndex.runtime().get(), params, jobs);
        }
        ReadOnlyIndex index = currentIndex.readable();
        int docId = params.docId();
        Optional<String> field = params.field();
        GetDocumentHandler.checkDocId(index, docId);
        if (field.isPresent()) {
            SearchQueries.checkField(index, field.get());
        }
        String what = field.orElse("the stored fields") + " of document " + docId;
        String description = "highlight " + what;
        if (!Highlighting.hasTerms(params.query())) {
            // Checked as a search would be, but there is nothing to mark.
            SearchQueries.query(index, params.query()).close();
            return new JobStarted(
                    jobs.start(description, DocumentHighlight.class, _ -> new DocumentHighlight(List.of())));
        }
        // Last: from here on, the job owns the analyzers and closes them.
        SearchQueries.AnalyzedQuery analyzed = SearchQueries.query(index, params.query());
        Analyzer analyzer;
        try {
            analyzer = Highlighting.analyzer(params.options());
        } catch (RpcException e) {
            analyzed.close();
            throw e;
        }
        Query query = analyzed.query();
        HighlightSpec spec = Highlighting.spec(params.options(), analyzer);
        long jobId = jobs.start(description + " for " + query, DocumentHighlight.class, progress -> {
            try (analyzed;
                    analyzer) {
                return highlight(index, query, spec, docId, field, what, progress);
            }
        });
        return new JobStarted(jobId);
    }

    private static DocumentHighlight highlight(
            ReadOnlyIndex index,
            Query query,
            HighlightSpec spec,
            int docId,
            Optional<String> field,
            String what,
            ScanProgress progress)
            throws RpcException {
        try {
            return Highlighting.toHighlight(
                    index.highlight(query, spec, docId, field, StartStoredScanParams.MAX_SPANS, progress));
        } catch (InvalidQueryException e) {
            throw SearchQueries.invalidQuery(e);
        } catch (IOException e) {
            throw new RpcException(ErrorCode.INDEX_NOT_READABLE, "Cannot highlight " + what + ": " + e.getMessage(), e);
        }
    }
}
