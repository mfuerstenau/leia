package io.github.mfuerstenau.leia.runtime.support;

import java.util.function.LongConsumer;

/**
 * Asks {@code progress} whether a check goes on, and remembers the first time it said no (ADR 0050).
 * A runtime's {@code CheckIndex} catches what a segment's check throws and goes on with the next
 * segment, so what {@code progress} threw is thrown again by {@link #rethrow} on every later read.
 */
public final class Cancellation {
    private final LongConsumer progress;
    // An immutable value, published through volatile (ADR 0088)
    @SuppressWarnings("java:S3077")
    private volatile RuntimeException stopped;

    private long lines;

    /** {@code progress} gets the number of lines reported so far, and throws to stop. */
    public Cancellation(LongConsumer progress) {
        this.progress = progress;
    }

    /** Counts a line of the report and asks {@code progress}. */
    public synchronized void line() {
        lines++;
        check();
    }

    /** Throws again what {@code progress} threw, if it did; cheap, so it is asked on every read. */
    public void rethrow() {
        if (stopped != null) {
            throw stopped;
        }
    }

    /** Asks {@code progress}, after throwing again what it threw before, if it did. */
    public synchronized void check() {
        rethrow();
        try {
            progress.accept(lines);
        } catch (RuntimeException e) {
            stopped = e;
            throw e;
        }
    }
}
