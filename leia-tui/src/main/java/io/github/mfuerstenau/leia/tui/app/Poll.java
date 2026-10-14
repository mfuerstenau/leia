package io.github.mfuerstenau.leia.tui.app;

/** A running job that the {@link Session} polls on every tick (ADR 0034 §4). */
public sealed interface Poll {

    /** Report back with {@link App#scanProgress} or {@link App#scanFailed}, under {@code scanId}. */
    record Scan(long scanId, long jobId) implements Poll {}

    /** Report back with {@link App#reconstructProgress} or {@link App#reconstructFailed}. */
    record Reconstruct(long jobId) implements Poll {}

    /** Report back with {@link App#filterProgress} or {@link App#filterFailed}, under {@code serial}. */
    record Filter(long serial, long jobId) implements Poll {}

    /** Report back with {@link App#documentExportProgress} or {@link App#exportFailed}, under {@code exportId}. */
    record DocumentExport(long exportId, long jobId) implements Poll {}

    /** Report back with {@link App#searchProgress} or {@link App#searchFailed}, under {@code searchId}. */
    record Search(long searchId, long jobId) implements Poll {}

    /** Report back with {@link App#explainProgress} or {@link App#explainFailed}, under {@code explainId}. */
    record Explain(long explainId, long jobId) implements Poll {}

    /** Report back with {@link App#highlightProgress} or {@link App#highlightFailed}, under {@code highlightId}. */
    record Highlight(long highlightId, long jobId) implements Poll {}

    /**
     * Report back with {@link App#documentMatchesProgress} or {@link App#documentMatchesFailed}, under
     * {@code matchesId} (ADR 0043).
     */
    record DocumentMatches(long matchesId, long jobId) implements Poll {}

    /**
     * Ask for the hits from {@code fromHit} on, and report back with {@link App#storedScanProgress} or
     * {@link App#storedScanFailed}, under {@code scanId}.
     */
    record StoredScan(long scanId, long jobId, int fromHit) implements Poll {}

    /**
     * Ask for the lines from {@code fromLine} on, and report back with {@link App#checkProgress} or
     * {@link App#checkFailed}, under {@code checkId}.
     */
    record Check(long checkId, long jobId, int fromLine) implements Poll {}

    /** Report back with {@link App#termsExportProgress} or {@link App#exportFailed}, under {@code exportId}. */
    record ExportTerms(long exportId, long jobId) implements Poll {}

    /** Report back with {@link App#autoStopWordsProgress} or {@link App#autoStopWordsFailed}, under {@code id}. */
    record AutoStopWords(long id, long jobId) implements Poll {}
}
