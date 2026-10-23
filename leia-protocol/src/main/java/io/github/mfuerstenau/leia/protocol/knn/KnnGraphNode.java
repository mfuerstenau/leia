package io.github.mfuerstenau.leia.protocol.knn;

/**
 * A node of a segment's HNSW graph (ADR 0085): a document's vector.
 *
 * @param ord the vector ordinal in the segment, from 0: the node's handle in {@code getKnnGraphNode}
 * @param docId the document id in the index
 * @param deleted whether the document is deleted; its node stays in the graph until a merge
 * @param topLevel the highest level the node is on
 */
public record KnnGraphNode(int ord, int docId, boolean deleted, int topLevel) {}
