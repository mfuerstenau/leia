package io.github.mfuerstenau.leia.protocol.knn;

import io.github.mfuerstenau.leia.protocol.search.KnnQuery;
import java.util.OptionalInt;

/**
 * Params of {@code startKnnRecall} (ADR 0086): how many of the exact {@code k} nearest documents an
 * approximate KNN search finds, for one target or for a sample of the field's documents.
 *
 * @param query an approximate KNN query: {@code exact} false, with {@code k}; its filter applies to
 *     both searches. Without {@code sample}, its target is the one checked. With {@code sample}, it
 *     has no target: each sampled document's vector is one, and the document itself is left out of
 *     both rankings
 * @param sample how many documents with a vector to check, from 1 to {@link #MAX_SAMPLE}, spread
 *     evenly over the segments; absent to check the query's own target
 */
public record StartKnnRecallParams(KnnQuery query, OptionalInt sample) {

    /** The largest {@code sample}. */
    public static final int MAX_SAMPLE = 1000;
}
