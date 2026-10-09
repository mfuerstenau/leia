package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;

/**
 * The outcome of a scan of the stored values (ADR 0027).
 *
 * @param scannedDocs the document ids gone through: maxDoc, deleted documents included even when
 *     they were skipped
 * @param matchedDocs documents with at least one value in which the pattern was found
 * @param matchedValues values in which the pattern was found
 * @param hitsTruncated whether more values matched than the scan kept as hits
 * @param timedOutValues values that were skipped because matching them took too long
 * @param timedOut the first {@link StartStoredScanParams#MAX_TIMED_OUT} of those
 * @param tookMillis how long the scan took
 */
public record StoredScanResult(
        int scannedDocs,
        int matchedDocs,
        long matchedValues,
        boolean hitsTruncated,
        long timedOutValues,
        List<ValueRef> timedOut,
        long tookMillis) {}
