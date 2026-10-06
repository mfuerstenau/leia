package io.github.mfuerstenau.leia.protocol.terms;

import java.util.Optional;

/**
 * Result of {@code getFieldTermScan}: the progress of the scan, and its outcome once it is done.
 *
 * @param termsScanned the terms scanned so far
 * @param terms the outcome; absent while the scan is running
 */
public record GetFieldTermScanResult(long termsScanned, Optional<FieldTerms> terms) {}
