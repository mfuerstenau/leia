package io.github.mfuerstenau.leia.protocol.knn;

import java.util.OptionalInt;

/**
 * Params of {@code getKnnGraphNode} (ADR 0085): a node of a segment's HNSW graph with its
 * neighbours, given either by {@code docId}, or by {@code segment} and {@code ord}.
 *
 * @param field a field with KNN vectors
 * @param segment the segment, as {@link KnnGraphSegment#segment()} gives it; present with {@code ord}
 * @param ord the node's vector ordinal in the segment; present with {@code segment}
 * @param docId the document whose vector is the node, from 0 to maxDoc - 1; it may be deleted
 */
public record GetKnnGraphNodeParams(String field, OptionalInt segment, OptionalInt ord, OptionalInt docId) {

    /** The node of document {@code docId}'s vector in {@code field}. */
    public static GetKnnGraphNodeParams ofDoc(String field, int docId) {
        return new GetKnnGraphNodeParams(field, OptionalInt.empty(), OptionalInt.empty(), OptionalInt.of(docId));
    }

    /** Node {@code ord} of {@code field}'s graph in segment {@code segment}. */
    public static GetKnnGraphNodeParams ofOrd(String field, int segment, int ord) {
        return new GetKnnGraphNodeParams(field, OptionalInt.of(segment), OptionalInt.of(ord), OptionalInt.empty());
    }
}
