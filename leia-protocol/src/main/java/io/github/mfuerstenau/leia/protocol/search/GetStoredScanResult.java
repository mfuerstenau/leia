package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;
import java.util.Optional;

/**
 * Result of {@code getStoredScan}: the hits of the scan so far, and its outcome once it is done.
 *
 * @param scannedDocs the document ids gone through so far, of maxDoc
 * @param matchedValues values in which the pattern was found so far, also those after the kept hits
 * @param hitCount the hits kept so far, at most {@link StartStoredScanParams#MAX_HITS}
 * @param hits the hits from {@code fromHit} on; all of them once the outcome is there
 * @param result the outcome; absent while the scan is running
 */
public record GetStoredScanResult(
        long scannedDocs,
        long matchedValues,
        int hitCount,
        List<StoredScanHit> hits,
        Optional<StoredScanResult> result) {}
