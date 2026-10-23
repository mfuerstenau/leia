package io.github.mfuerstenau.leia.protocol.knn;

import java.util.Optional;

/**
 * The HNSW graph of a KNN vector field in one segment (ADR 0085).
 *
 * @param segment the segment's position in the open reader, from 0: the handle {@code
 *     getKnnGraphNodes} and {@code getKnnGraphNode} take, valid until the index is reopened
 * @param index the position of the segment's index directory among those opened (ADR 0021), from 0
 * @param name the segment's name, such as {@code _0}; absent if the reader doesn't say
 * @param docBase the document id of the segment's first document
 * @param maxDoc the documents of the segment, deleted ones included
 * @param vectors the vectors of the field in the segment, deleted documents' included: the nodes of
 *     its graph
 * @param shape the graph's levels; absent if the segment has no graph leia can read: its format has
 *     none (a flat one, or Lucene 9.0 to 9.4's), or the segment was too small for Lucene to build one,
 *     and a search compares every vector
 */
public record KnnGraphSegment(
        int segment,
        int index,
        Optional<String> name,
        int docBase,
        int maxDoc,
        int vectors,
        Optional<KnnGraphShape> shape) {}
