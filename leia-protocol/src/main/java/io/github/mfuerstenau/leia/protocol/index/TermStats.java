package io.github.mfuerstenau.leia.protocol.index;

/**
 * Postings statistics of a field, summed over all segments. They include deleted documents.
 *
 * @param docCount documents that have at least one term in the field
 * @param sumDocFreq the sum of the document frequencies of all terms
 * @param sumTotalTermFreq the sum of the term frequencies of all terms; equal to {@code sumDocFreq}
 *     if frequencies are not indexed
 */
public record TermStats(int docCount, long sumDocFreq, long sumTotalTermFreq) {}
