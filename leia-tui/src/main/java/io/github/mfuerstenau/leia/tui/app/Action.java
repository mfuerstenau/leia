package io.github.mfuerstenau.leia.tui.app;

import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeParams;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.filter.StepFilterParams;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsParams;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.terms.PostingsFilter;
import io.github.mfuerstenau.leia.protocol.terms.StartExportTermsParams;
import io.github.mfuerstenau.leia.tui.documents.DocFilter.FilterStep;
import io.github.mfuerstenau.leia.tui.documents.Documents;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What the {@link Session} has to do after a key: {@link App} makes no calls itself. Each action
 * says how the outcome is reported back to the app.
 */
public sealed interface Action {

    /** Quit leia. */
    record Quit() implements Action {}

    /**
     * Open this index through this {@code Directory}, with or without a reader, and fetch its
     * statistics if it has a reader. The prompt shows "Opening…" until {@link App#opened}, {@link
     * App#openedWithoutReader} or {@link App#openFailed} reports the outcome.
     */
    record Open(String path, DirectoryImpl directory, OpenMode mode) implements Action {}

    /** Close the open index, and report back with {@link App#closed}. */
    record Close() implements Action {}

    /**
     * Reopen the open index if it changed, and report back with {@link App#reopened}, {@link
     * App#reopenUnchanged} or {@link App#reopenFailed} (ADR 0019).
     */
    record Reopen() implements Action {}

    /**
     * Start the term scan of this field for its top {@code topN} terms, and report back with {@link
     * App#scanStarted} or {@link App#scanFailed}, under {@code scanId}.
     */
    record StartScan(long scanId, String field, int topN) implements Action {}

    /** Cancel this scan job. The app has forgotten the scan already, so nothing is reported back. */
    record CancelScan(long jobId) implements Action {}

    /**
     * Fetch the commits of the index directory at this position, and report back with {@link
     * App#commitsLoaded} or {@link App#commitsFailed}.
     */
    record LoadCommits(int index) implements Action {}

    /**
     * Fetch a commit of the index directory at this position whole, by generation, and report back
     * with {@link App#commitLoaded} or {@link App#commitFailed}.
     */
    record LoadCommit(int index, long generation) implements Action {}

    /**
     * Fetch a page of the terms of a field, and report back with {@link App#termsLoaded} or {@link
     * App#termsFailed}.
     *
     * @param from where the page starts; empty for the first term
     * @param more whether the page continues the shown terms
     */
    record LoadTerms(String field, Optional<BytesValue> from, boolean more) implements Action {}

    /**
     * Fetch a page of the postings of a term, and report back with {@link App#postingsLoaded} or
     * {@link App#postingsFailed}.
     *
     * @param more whether the page continues the shown postings
     * @param filter the filter to read the postings through (ADR 0032)
     */
    record LoadPostings(String field, BytesValue term, int fromDoc, boolean more, Optional<PostingsFilter> filter)
            implements Action {}

    /**
     * Cancel the jobs in {@code cancel}, fetch this document, and report back with {@link
     * App#documentLoaded} or {@link App#documentFailed}. With {@code locate}, first find out whether
     * it matches that filter, and report back with {@link App#filterStepped} or {@link
     * App#filterFailed} (ADR 0031).
     */
    record LoadDocument(int docId, List<Long> cancel, Optional<Documents.Locate> locate) implements Action {}

    /**
     * Fetch a stored value whole, and report back with {@link App#storedValueLoaded} or {@link
     * App#storedValueFailed}.
     */
    record LoadStoredValue(int docId, String field, int index) implements Action {}

    /**
     * Fetch the term vector of a field of a document, and report back with {@link
     * App#termVectorLoaded} or {@link App#termVectorFailed}.
     */
    record LoadTermVector(int docId, String field) implements Action {}

    /**
     * Fetch where a term occurs in a document, and report back with {@link App#positionsLoaded} or
     * {@link App#positionsFailed}.
     */
    record LoadPositions(int docId, String field, BytesValue term) implements Action {}

    /** Put this text on the terminal's clipboard (OSC 52); nothing is reported back. */
    record Copy(String text) implements Action {}

    /**
     * Cancel the jobs in {@code cancel}, then start rebuilding the terms {@code field} of a document
     * was indexed with, and report back with {@link App#reconstructStarted} or {@link
     * App#reconstructStartFailed} (ADR 0029).
     */
    record StartReconstruct(int docId, String field, List<Long> cancel) implements Action {}

    /** Cancel this rebuild job. The app has forgotten the rebuild already, so nothing is reported back. */
    record CancelReconstruct(long jobId) implements Action {}

    /**
     * Cancel the job {@code cancel} and release the filter {@code release}, which this filter
     * replaces, then start building filter {@code serial}, and report back with {@link
     * App#filterStarted} or {@link App#filterFailed} (ADR 0031).
     */
    record StartFilter(long serial, StartFilterParams params, OptionalLong cancel, OptionalLong release)
            implements Action {}

    /**
     * Find a match of filter {@code serial}, and report back with {@link App#filterStepped} or {@link
     * App#filterStepFailed}.
     */
    record StepFilter(long serial, StepFilterParams params, FilterStep step) implements Action {}

    /**
     * Cancel the job that builds a filter, or release the filter. The app has forgotten it already,
     * so nothing is reported back.
     */
    record ClearFilter(OptionalLong cancel, OptionalLong release) implements Action {}

    /**
     * Fetch document {@code docId} whole for export {@code exportId} (ADR 0030), and report back with
     * {@link App#documentExportLoaded} or {@link App#exportFailed}.
     */
    record LoadDocumentExport(long exportId, int docId) implements Action {}

    /**
     * Start rebuilding the indexed terms of the document of export {@code exportId}, and report back
     * with {@link App#exportStarted} or {@link App#exportFailed}.
     */
    record StartDocumentExport(long exportId, int docId) implements Action {}

    /**
     * Start export {@code exportId} of the terms of a field (ADR 0022), and report back with {@link
     * App#exportStarted} or {@link App#exportFailed}.
     */
    record StartExportTerms(long exportId, StartExportTermsParams params) implements Action {}

    /** Cancel this export job. The app has forgotten the export already, so nothing is reported back. */
    record CancelExport(long jobId) implements Action {}

    /**
     * Write the exported document to {@code path}, never into {@code indexDirs}, and report back with
     * {@link App#documentExported} or {@link App#exportFailed}.
     */
    record WriteExport(long exportId, int docId, String path, String text, boolean overwrite, List<String> indexDirs)
            implements Action {}

    /**
     * Fetch what custom analyzers can be built from, and report back with {@link
     * App#componentsLoaded} or {@link App#componentsFailed}.
     */
    record LoadAnalysisComponents() implements Action {}

    /** Analyze a text, and report back with {@link App#analyzed} or {@link App#analyzeFailed}. */
    record Analyze(AnalyzeParams params) implements Action {}

    /**
     * Cancel the jobs in {@code cancel}, then start search {@code searchId}, and report back with
     * {@link App#searchStarted} or {@link App#searchFailed}.
     */
    record StartSearch(long searchId, StartSearchParams params, List<Long> cancel) implements Action {}

    /**
     * Cancel the jobs in {@code cancel}, then start explanation {@code explainId}, and report back
     * with {@link App#explainStarted} or {@link App#explainFailed}.
     */
    record StartExplain(long explainId, StartExplainParams params, List<Long> cancel) implements Action {}

    /**
     * Cancel the jobs in {@code cancel}, then start regex scan {@code scanId}, and report back with
     * {@link App#storedScanStarted} or {@link App#storedScanFailed}.
     */
    record StartStoredScan(long scanId, StartStoredScanParams params, List<Long> cancel) implements Action {}

    /** Cancel these search, explanation and scan jobs; the app has forgotten them, so nothing is reported back. */
    record CancelSearch(List<Long> jobs) implements Action {}

    /**
     * Start check {@code checkId} of the open path at this level, and report back with {@link
     * App#checkStarted} or {@link App#checkFailed} (ADR 0022).
     */
    record StartCheck(long checkId, int level) implements Action {}

    /** Cancel this check job. The app has stopped the check already, so nothing is reported back. */
    record CancelCheck(long jobId) implements Action {}

    /**
     * Fetch the loaded jars, and report back with {@link App#jarsLoaded} or {@link App#jarsFailed}
     * (ADR 0024).
     */
    record LoadJars() implements Action {}

    /**
     * Add these jars, and report back with {@link App#jarsAdded} or {@link App#addJarsFailed}. The
     * popup shows "Loading…" until the outcome is reported.
     */
    record AddJars(AddJarsParams params) implements Action {}
}
