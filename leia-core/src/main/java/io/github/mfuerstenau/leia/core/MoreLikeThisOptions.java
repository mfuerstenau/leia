package io.github.mfuerstenau.leia.core;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.search.IndexSearcher;

/**
 * How Lucene's {@link MoreLikeThis} picks the terms of a document (ADR 0013).
 *
 * @param fields the fields to take terms from, at least one and none twice
 * @param minTermFreq terms that occur fewer times in the document are left out
 * @param minDocFreq terms that occur in fewer documents are left out
 * @param maxDocFreq terms that occur in more documents are left out; empty for no limit
 * @param maxQueryTerms how many of the best terms the query has, from 1 to {@link
 *     IndexSearcher#getMaxClauseCount()}
 * @param boost whether each term is boosted by its score, relative to the best term's
 */
public record MoreLikeThisOptions(
        List<String> fields,
        int minTermFreq,
        int minDocFreq,
        OptionalInt maxDocFreq,
        int maxQueryTerms,
        boolean boost) {

    /** @throws IllegalArgumentException for no field, a field listed twice, or a number out of range */
    public MoreLikeThisOptions {
        fields = List.copyOf(fields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("fields must name at least one field");
        }
        var seen = new HashSet<String>();
        for (String field : fields) {
            if (!seen.add(field)) {
                throw new IllegalArgumentException("the field '" + field + "' is listed twice");
            }
        }
        if (minTermFreq < 0) {
            throw new IllegalArgumentException("minTermFreq must be 0 or more, not " + minTermFreq);
        }
        if (minDocFreq < 0) {
            throw new IllegalArgumentException("minDocFreq must be 0 or more, not " + minDocFreq);
        }
        if (maxDocFreq.isPresent() && maxDocFreq.getAsInt() < 0) {
            throw new IllegalArgumentException("maxDocFreq must be 0 or more, not " + maxDocFreq.getAsInt());
        }
        // More clauses than this, and MoreLikeThis drops the rest without a word.
        int maxClauses = IndexSearcher.getMaxClauseCount();
        if (maxQueryTerms < 1 || maxQueryTerms > maxClauses) {
            throw new IllegalArgumentException(
                    "maxQueryTerms must be from 1 to " + maxClauses + ", not " + maxQueryTerms);
        }
    }

    /** Lucene's defaults for {@code fields}. */
    public static MoreLikeThisOptions defaults(List<String> fields) {
        return new MoreLikeThisOptions(
                fields,
                MoreLikeThis.DEFAULT_MIN_TERM_FREQ,
                MoreLikeThis.DEFAULT_MIN_DOC_FREQ,
                OptionalInt.empty(),
                MoreLikeThis.DEFAULT_MAX_QUERY_TERMS,
                MoreLikeThis.DEFAULT_BOOST);
    }
}
