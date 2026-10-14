package io.github.mfuerstenau.leia.core;

import java.util.Optional;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;

/**
 * One field of an index: how it is indexed, from the merged {@link
 * org.apache.lucene.index.FieldInfos}, and its postings statistics.
 *
 * @param name the field name
 * @param number the field number
 * @param indexOptions what the postings hold; {@link IndexOptions#NONE} if the field is not indexed
 * @param hasNorms whether norms are stored
 * @param hasPayloads whether any position has a payload
 * @param hasTermVectors whether term vectors are stored
 * @param docValuesType the doc values type; {@link DocValuesType#NONE} if there are none
 * @param pointDimensionCount dimensions of the point values; 0 if there are none
 * @param pointNumBytes bytes per point dimension; 0 if there are none
 * @param vectorDimension dimensions of the KNN vectors; 0 if there are none
 * @param vectorEncoding how the KNN vectors are encoded; Lucene's default if there are none
 * @param vectorSimilarity how the KNN vectors are compared; Lucene's default if there are none
 * @param terms postings statistics; empty if no segment has postings for the field
 */
public record FieldStats(
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
        VectorEncoding vectorEncoding,
        VectorSimilarityFunction vectorSimilarity,
        Optional<TermStats> terms) {

    /**
     * Postings statistics of a field, summed over all segments. They are stored per segment, so they
     * are cheap, and they include deleted documents. The number of unique terms is not here: across
     * segments it can only be found by iterating over all terms.
     *
     * @param docCount documents that have at least one term in the field
     * @param sumDocFreq the sum of the document frequencies of all terms
     * @param sumTotalTermFreq the sum of the term frequencies of all terms; equal to {@code
     *     sumDocFreq} if frequencies are not indexed
     */
    public record TermStats(int docCount, long sumDocFreq, long sumTotalTermFreq) {}
}
