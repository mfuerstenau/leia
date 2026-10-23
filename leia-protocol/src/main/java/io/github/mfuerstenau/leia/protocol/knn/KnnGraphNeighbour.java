package io.github.mfuerstenau.leia.protocol.knn;

/**
 * A neighbour of a node in its graph (ADR 0085).
 *
 * @param score the field's similarity of the two documents' vectors, as an exact KNN search scores
 *     them: higher is nearer
 */
public record KnnGraphNeighbour(KnnGraphNode node, float score) {}
