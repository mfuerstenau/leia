package io.github.mfuerstenau.leia.protocol.document;

import java.util.Optional;

/**
 * Result of {@code getStoredFieldsScan}: the progress of the scan, and its outcome once it is done
 * (ADR 0113).
 *
 * @param docsScanned the documents gone through so far, deleted ones included; a segment the cache
 *     knows counts at once
 * @param fields the outcome; absent while the scan is running
 */
public record GetStoredFieldsScanResult(long docsScanned, Optional<StoredFields> fields) {}
