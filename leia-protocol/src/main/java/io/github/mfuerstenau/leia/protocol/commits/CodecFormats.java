package io.github.mfuerstenau.leia.protocol.commits;

import java.util.Optional;

/**
 * The formats of a segment's codec: the name of a named format (postings, doc values, KNN
 * vectors), otherwise the simple class name (ADR 0009).
 *
 * @param points the points format; absent before Lucene 6, which had no points (ADR 0046)
 * @param knnVectors the KNN vectors format; absent before Lucene 9, which had no KNN vectors
 */
public record CodecFormats(
        String postings,
        String docValues,
        String storedFields,
        String termVectors,
        String norms,
        Optional<String> points,
        Optional<String> knnVectors,
        String fieldInfos,
        String segmentInfo,
        String liveDocs,
        String compound) {}
