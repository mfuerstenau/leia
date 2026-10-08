package io.github.mfuerstenau.leia.protocol.terms;

import java.util.Optional;

/**
 * Result of {@code getExportTerms}: the progress of the export, and its outcome once it is done.
 *
 * @param termsWritten the terms written so far
 * @param result the outcome; absent while the export is running
 */
public record GetExportTermsResult(long termsWritten, Optional<ExportTermsResult> result) {}
