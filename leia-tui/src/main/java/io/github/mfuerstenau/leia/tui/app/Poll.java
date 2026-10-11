package io.github.mfuerstenau.leia.tui.app;

/** A running job that the {@link Session} polls on every tick (ADR 0034 §4). */
public sealed interface Poll {

    /** Report back with {@link App#scanProgress} or {@link App#scanFailed}, under {@code scanId}. */
    record Scan(long scanId, long jobId) implements Poll {}
}
