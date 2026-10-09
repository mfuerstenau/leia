package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;
import java.util.Optional;

/**
 * One page of the hits of a search (ADR 0010).
 *
 * @param query the query as Lucene writes it
 * @param rewrittenQuery the query after rewriting, as Lucene writes it
 * @param totalHits how many documents match
 * @param totalHitsRelation whether {@code totalHits} is exact or a lower bound
 * @param hits the hits of the page, best first or in the order of the sort
 * @param tookMillis how long the search took, in milliseconds
 * @param moreLikeThisTerms for a {@code moreLikeThis} query only, the terms it chose, best first
 *     (ADR 0028)
 */
public record SearchResults(
        String query,
        String rewrittenQuery,
        long totalHits,
        TotalHitsRelation totalHitsRelation,
        List<Hit> hits,
        long tookMillis,
        Optional<List<LikeTerm>> moreLikeThisTerms) {

    /**
     * The longest stored value, in bytes, that a hit has whole. A longer {@code STRING} or {@code
     * BINARY} value is cut, as in {@code getDocument} (ADR 0008).
     */
    public static final int MAX_VALUE_LENGTH = 256;
}
