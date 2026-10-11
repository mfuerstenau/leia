package io.github.mfuerstenau.leia.tui.app;

import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.terms.PostingsFilter;
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
     * App#documentLoaded} or {@link App#documentFailed}.
     *
     * @param locate the filter to locate the document in once it is in, if any (ADR 0031)
     */
    record LoadDocument(int docId, List<Long> cancel, OptionalLong locate) implements Action {}

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
}
