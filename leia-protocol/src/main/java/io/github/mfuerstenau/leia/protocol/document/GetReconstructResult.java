package io.github.mfuerstenau.leia.protocol.document;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Result of {@code getReconstruct} (ADR 0029): the progress of the job, and its outcome once it is
 * done.
 *
 * @param scanned the terms and points gone through so far
 * @param total the terms and points the job goes through: the terms of the fields it rebuilds from
 *     the postings and the points of the fields, in the document's segment (ADR 0041); absent if the
 *     segment doesn't know how many terms a field has
 * @param result the outcome; absent while the job is running
 */
public record GetReconstructResult(long scanned, OptionalLong total, Optional<ReconstructResult> result) {}
