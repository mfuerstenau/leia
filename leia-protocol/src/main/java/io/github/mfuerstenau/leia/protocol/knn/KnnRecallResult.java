package io.github.mfuerstenau.leia.protocol.knn;

import java.util.List;
import java.util.OptionalDouble;

/**
 * The outcome of a recall check (ADR 0086). A target's recall is the share of the exact {@code k}
 * nearest documents that the approximate search found; 1 if the exact search found none.
 *
 * @param targets the targets checked
 * @param meanRecall the targets' recall, averaged
 * @param minRecall the lowest recall of a target
 * @param perfectTargets the targets whose approximate search found all of the exact nearest
 * @param meanScoreDrift for the documents both searches found, how far the approximate score is
 *     from the exact one, averaged: what a quantized format changes; absent if no document was found
 *     by both
 * @param maxScoreDrift the largest such difference; absent as {@code meanScoreDrift}
 * @param worst the targets with the lowest recall, lowest first, at most {@link #MAX_WORST}; a check
 *     of one target has it as its only entry
 */
public record KnnRecallResult(
        String field,
        int k,
        int targets,
        double meanRecall,
        double minRecall,
        int perfectTargets,
        OptionalDouble meanScoreDrift,
        OptionalDouble maxScoreDrift,
        List<KnnRecallTarget> worst) {

    /** The most targets in {@code worst}. */
    public static final int MAX_WORST = 10;
}
