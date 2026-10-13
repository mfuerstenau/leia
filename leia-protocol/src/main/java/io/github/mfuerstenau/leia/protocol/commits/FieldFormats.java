package io.github.mfuerstenau.leia.protocol.commits;

import java.util.Optional;

/**
 * The formats of a field in a segment, as the segment's field infos name them (ADR 0037).
 *
 * @param field the field name
 * @param postings the postings format, such as {@code Lucene104}; absent if the field isn't indexed
 * @param docValues the doc values format, such as {@code Lucene90}; absent if the field has no doc
 *     values
 * @param knnVectors the KNN vectors format, such as {@code Lucene99HnswVectorsFormat}; absent if the
 *     field has no vectors
 */
public record FieldFormats(
        String field, Optional<String> postings, Optional<String> docValues, Optional<String> knnVectors) {}
