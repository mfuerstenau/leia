package io.github.mfuerstenau.leia.protocol.knn;

import java.util.Optional;

/**
 * Result of {@code getKnnRecall} (ADR 0086): the progress of the check, and its outcome once it is
 * done.
 *
 * @param targetsChecked the targets whose two searches are done
 * @param recall the outcome; absent while the check is running
 */
public record GetKnnRecallResult(long targetsChecked, Optional<KnnRecallResult> recall) {}
