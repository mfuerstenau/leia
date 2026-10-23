package io.github.mfuerstenau.leia.protocol.knn;

/**
 * Params of {@code getKnnGraph} (ADR 0085): the HNSW graphs of a KNN vector field, segment by segment.
 *
 * @param field a field with KNN vectors
 */
public record GetKnnGraphParams(String field) {}
