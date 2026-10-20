package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.search.HighlightOptions;
import io.github.mfuerstenau.leia.protocol.search.StartHighlightParams;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.Highlight;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.search.DocumentHighlight;
import io.github.mfuerstenau.leia.server.search.FieldHighlight;
import io.github.mfuerstenau.leia.server.search.Highlighting;
import io.github.mfuerstenau.leia.server.search.Highlights;
import io.github.mfuerstenau.leia.server.search.OffsetSource;
import io.github.mfuerstenau.leia.server.search.TextHighlighter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * Highlighting on a runtime with a {@link Highlight} group (ADR 0063): the runtime's highlighter finds
 * the passages, and leia-core's {@link Highlights} turns them into what Lucene 10's highlighting gives
 * (ADR 0042, ADR 0043).
 */
public final class RuntimeHighlights {

    private RuntimeHighlights() {}

    /**
     * {@code startHighlight}: checks the document, the field and the query at once, then finds the
     * query's matches in the document's stored text in a job. A KNN query has no terms, so nothing is
     * marked.
     */
    public static JobStarted startHighlight(OpenRuntime open, StartHighlightParams params, Jobs jobs)
            throws RpcException {
        RuntimeIndex index = open.index();
        Highlight highlight = index.highlight().orElseThrow(open::notSupported);
        Search search = index.search().orElseThrow(open::notSupported);
        Documents documents = index.documents().orElseThrow(open::notSupported);
        int docId = params.docId();
        Optional<String> field = params.field();
        RuntimeResults.checkDocId(index, docId);
        if (field.isPresent()) {
            RuntimeResults.checkField(index, field.get());
        }
        String what = field.orElse("the stored fields") + " of document " + docId;
        String description = "highlight " + what;
        // Checked as a search would be, even without terms to mark.
        Search.ParsedQuery query = RuntimeResults.parsedQuery(open, search, params.query());
        if (!Highlighting.hasTerms(params.query())) {
            return new JobStarted(jobs.start(
                    description,
                    io.github.mfuerstenau.leia.protocol.search.DocumentHighlight.class,
                    _ -> new io.github.mfuerstenau.leia.protocol.search.DocumentHighlight(List.of())));
        }
        Search.AnalyzerSpec analyzer = RuntimeAnalyzers.spec(params.options().analyzer());
        boolean requireFieldMatch = params.options().requireFieldMatch();
        long jobId = jobs.start(
                description + " for " + query + " with Lucene " + open.runtime().luceneVersion(),
                io.github.mfuerstenau.leia.protocol.search.DocumentHighlight.class,
                progress -> {
                    try {
                        // A deleted document is highlighted too, as on Lucene 10.
                        List<Documents.StoredDocument> stored =
                                documents.storedValues(docId, docId + 1, field.map(Set::of), true);
                        Map<String, List<RuntimeIndex.StoredValue>> values = stored.isEmpty()
                                ? Map.of()
                                : RuntimeResults.storedDocument(stored.getFirst())
                                        .values();
                        DocumentHighlight found = Highlights.document(
                                textHighlighter(highlight, query, requireFieldMatch, analyzer, progress::scanned),
                                docId,
                                values,
                                StartStoredScanParams.MAX_SPANS);
                        return Highlighting.toHighlight(found);
                    } catch (AnalyzerFailed e) {
                        throw RuntimeAnalyzers.failed(open.runtime(), e.getCause());
                    } catch (IOException e) {
                        throw new RpcException(
                                ErrorCode.INDEX_NOT_READABLE, "Cannot highlight " + what + ": " + e.getMessage(), e);
                    }
                });
        return new JobStarted(jobId);
    }

    /**
     * The best passages of each hit of {@code results}, per field, in the order of each hit's
     * fields, as {@code startSearch} on Lucene 10 finds them.
     *
     * @param analyzer the analyzer of {@code options}, as the runtime builds it
     * @throws RpcException {@code NOT_SUPPORTED} if the runtime can't build the analyzer, {@code
     *     INVALID_ANALYZER} if it is wrong
     */
    static List<List<FieldHighlight>> passages(
            OpenRuntime open,
            Highlight highlight,
            Search.ParsedQuery query,
            HighlightOptions options,
            Search.AnalyzerSpec analyzer,
            Search.SearchResults results,
            LongConsumer progress)
            throws IOException, RpcException {
        int[] docIds = results.hits().stream().mapToInt(Search.Hit::docId).toArray();
        List<Map<String, List<RuntimeIndex.StoredValue>>> stored =
                results.hits().stream().map(Search.Hit::fields).toList();
        try {
            return Highlights.passages(
                    textHighlighter(highlight, query, options.requireFieldMatch(), analyzer, progress),
                    options.maxPassages(),
                    docIds,
                    stored);
        } catch (AnalyzerFailed e) {
            throw RuntimeAnalyzers.failed(open.runtime(), e.getCause());
        }
    }

    /**
     * The runtime's {@code highlight} as leia-core's {@link TextHighlighter}. An analyzer the runtime
     * can't build is thrown as {@link AnalyzerFailed}.
     */
    static TextHighlighter textHighlighter(
            Highlight highlight,
            Search.ParsedQuery query,
            boolean requireFieldMatch,
            Search.AnalyzerSpec analyzer,
            LongConsumer progress) {
        return (fields, docIds, maxPassages, mode) -> {
            boolean wholeValues = mode == TextHighlighter.Mode.VALUES;
            Highlight.Options options = new Highlight.Options(
                    requireFieldMatch,
                    analyzer,
                    wholeValues,
                    wholeValues ? Highlights.MAX_DOCUMENT_CHARS : Highlights.MAX_HIT_CHARS);
            Highlight.Highlighted highlighted;
            try {
                highlighted = highlight.highlight(query, options, fields, docIds, maxPassages, progress);
            } catch (Search.UnsupportedAnalyzerException | Search.InvalidAnalyzerException e) {
                throw new AnalyzerFailed(e);
            }
            Map<String, OffsetSource> sources = new HashMap<>();
            highlighted.sources().forEach((field, source) -> sources.put(field, offsetSource(source)));
            Map<String, List<Optional<TextHighlighter.FoundText>>> found = new HashMap<>();
            highlighted
                    .found()
                    .forEach((field, perDoc) -> found.put(
                            field,
                            perDoc.stream()
                                    .map(text -> text.map(RuntimeHighlights::foundText))
                                    .toList()));
            return new TextHighlighter.Highlighted(Map.copyOf(sources), Map.copyOf(found));
        };
    }

    private static TextHighlighter.FoundText foundText(Highlight.FieldText text) {
        return new TextHighlighter.FoundText(
                offsetSource(text.source()),
                text.content(),
                text.passages().stream()
                        .map(passage -> new TextHighlighter.FoundPassage(
                                passage.start(),
                                passage.end(),
                                passage.score(),
                                passage.matchStarts(),
                                passage.matchEnds()))
                        .toList());
    }

    private static OffsetSource offsetSource(Highlight.OffsetSource source) {
        return switch (source) {
            case POSTINGS -> OffsetSource.POSTINGS;
            case TERM_VECTORS -> OffsetSource.TERM_VECTORS;
            case POSTINGS_WITH_TERM_VECTORS -> OffsetSource.POSTINGS_WITH_TERM_VECTORS;
            case ANALYSIS -> OffsetSource.ANALYSIS;
            case NONE -> OffsetSource.NONE;
        };
    }

    /**
     * An analyzer the runtime can't build, unsupported or invalid, passed through core's {@link
     * TextHighlighter}.
     */
    private static final class AnalyzerFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AnalyzerFailed(Exception cause) {
            super(cause);
        }

        @Override
        public synchronized Exception getCause() {
            return (Exception) super.getCause();
        }
    }
}
