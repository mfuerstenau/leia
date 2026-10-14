package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.Optional;

/**
 * Result of {@code getAutoStopWords}: the progress of the scan, and its outcome once it is done.
 *
 * @param termsScanned the terms scanned so far, over all fields
 * @param stopWords the outcome; absent while the scan is running
 */
public record GetAutoStopWordsResult(long termsScanned, Optional<AutoStopWords> stopWords) {}
