package io.github.mfuerstenau.leia.protocol.document;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Result of {@code getReconstruct} (ADR 0029): the progress of the job, and its outcome once it is
 * done.
 *
 * @param scannedTerms the terms gone through so far
 * @param totalTerms the terms the job goes through: those of the fields it rebuilds from the
 *     postings, in the document's segment; absent if the segment doesn't know
 * @param result the outcome; absent while the job is running
 */
public record GetReconstructResult(long scannedTerms, OptionalLong totalTerms, Optional<ReconstructResult> result) {}
