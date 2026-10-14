package io.github.mfuerstenau.leia.protocol.index;

/**
 * How a field's KNN vectors are compared, as Lucene's {@code VectorSimilarityFunction} (ADR 0040).
 */
public enum VectorSimilarity {
    EUCLIDEAN,
    DOT_PRODUCT,
    COSINE,
    MAXIMUM_INNER_PRODUCT
}
