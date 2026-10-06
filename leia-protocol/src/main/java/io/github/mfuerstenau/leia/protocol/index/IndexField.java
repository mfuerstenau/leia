package io.github.mfuerstenau.leia.protocol.index;

import java.util.Optional;

/**
 * One field of an index: how it is indexed, and its postings statistics.
 *
 * @param name the field name
 * @param number the field number
 * @param indexOptions what the postings hold; {@code NONE} if the field is not indexed
 * @param hasNorms whether norms are stored
 * @param hasPayloads whether any position has a payload
 * @param hasTermVectors whether term vectors are stored
 * @param docValuesType the doc values type; {@code NONE} if there are none
 * @param pointDimensionCount dimensions of the point values; 0 if there are none
 * @param pointNumBytes bytes per point dimension; 0 if there are none
 * @param vectorDimension dimensions of the KNN vectors; 0 if there are none
 * @param terms postings statistics; absent if no segment has postings for the field
 */
public record IndexField(
        String name,
        int number,
        IndexOptions indexOptions,
        boolean hasNorms,
        boolean hasPayloads,
        boolean hasTermVectors,
        DocValuesType docValuesType,
        int pointDimensionCount,
        int pointNumBytes,
        int vectorDimension,
        Optional<TermStats> terms) {}
