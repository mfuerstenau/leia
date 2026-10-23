package io.github.mfuerstenau.leia.protocol.knn;

/**
 * Params of {@code getKnnGraphNodes} (ADR 0085): a page of the nodes of one level of a segment's
 * HNSW graph, in ordinal order.
 *
 * @param field a field with KNN vectors
 * @param segment the segment, as {@link KnnGraphSegment#segment()} gives it
 * @param level the level, from 0 to the graph's top level
 * @param offset the position of the page's first node among the level's nodes, from 0 to their
 *     number
 * @param limit the most nodes of the page, from 1 to {@link #MAX_NODES}
 */
public record GetKnnGraphNodesParams(String field, int segment, int level, int offset, int limit) {

    /** The largest {@code limit}. */
    public static final int MAX_NODES = 1000;
}
