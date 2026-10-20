package io.github.mfuerstenau.leia.server.job;

import java.util.concurrent.CancellationException;

/**
 * Receives the progress of a job's scan, and can stop it (ADR 0005). The scan calls {@link #scanned} regularly and
 * once more at the end, from its own thread or, for a check (ADR 0022), from the threads that read,
 * but never from two threads at once.
 */
@FunctionalInterface
public interface ScanProgress {

    /** Ignores the progress and never stops the scan. */
    ScanProgress NONE = scanned -> {};

    /**
     * Reports how far the scan has got.
     *
     * @param scanned the items scanned so far, such as terms
     * @throws CancellationException to stop the scan; the scan passes it on to its caller
     */
    void scanned(long scanned);
}
