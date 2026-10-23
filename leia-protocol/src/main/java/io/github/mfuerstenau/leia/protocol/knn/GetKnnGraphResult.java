package io.github.mfuerstenau.leia.protocol.knn;

import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.index.VectorSimilarity;
import java.util.List;

/**
 * Result of {@code getKnnGraph} (ADR 0085): a KNN vector field and the graph of each segment that
 * has vectors in it.
 *
 * @param dimension the components of each vector
 * @param similarity how the graph compares vectors, and the neighbours' scores are computed
 * @param segments the segments with vectors in the field, in reader order
 */
public record GetKnnGraphResult(
        String field,
        int dimension,
        VectorEncoding encoding,
        VectorSimilarity similarity,
        List<KnnGraphSegment> segments) {}
