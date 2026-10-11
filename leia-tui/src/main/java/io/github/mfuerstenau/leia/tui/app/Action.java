package io.github.mfuerstenau.leia.tui.app;

import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;

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
}
