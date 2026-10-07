package io.github.mfuerstenau.leia.protocol.commits;

/**
 * The formats of a segment's codec: the name of a named format (postings, doc values, KNN
 * vectors), otherwise the simple class name (ADR 0009).
 */
public record CodecFormats(
        String postings,
        String docValues,
        String storedFields,
        String termVectors,
        String norms,
        String points,
        String knnVectors,
        String fieldInfos,
        String segmentInfo,
        String liveDocs,
        String compound) {}
