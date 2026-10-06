package io.github.mfuerstenau.leia.core;

import java.util.concurrent.CancellationException;

/**
 * Receives the progress of a scan, and can stop it. The scan calls {@link #scanned} from its own
 * thread, regularly and once more at the end.
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
