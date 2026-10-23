package io.github.mfuerstenau.leia.protocol.knn;

import java.util.List;
import java.util.OptionalInt;

/**
 * A target of a recall check (ADR 0086).
 *
 * @param docId the document whose vector is the target; absent for a target vector of the query
 * @param recall the share of the exact nearest documents that the approximate search found
 * @param missed the exact nearest documents that the approximate search didn't find, in their exact
 *     order
 */
public record KnnRecallTarget(OptionalInt docId, double recall, List<KnnRecallMiss> missed) {}
