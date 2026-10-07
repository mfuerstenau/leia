package io.github.mfuerstenau.leia.protocol.search;

import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import java.util.List;
import java.util.OptionalInt;

/**
 * Documents like one document of the index, by the terms that Lucene's {@code MoreLikeThis} picks
 * from it (ADR 0013). A field's terms come from the document's term vector, or else from its stored
 * strings, analyzed with {@code analyzer} (ADR 0014).
 *
 * @param docId the source document, from 0 to maxDoc - 1; it may be deleted
 * @param fields the indexed fields to take terms from, at least one and none twice
 * @param minTermFreq terms that occur fewer times in the document are left out (Lucene: 2)
 * @param minDocFreq terms that occur in fewer documents are left out (Lucene: 5)
 * @param maxDocFreq terms that occur in more documents are left out; absent for no limit
 * @param maxQueryTerms how many of the best terms the query has, from 1 to {@code MAX_QUERY_TERMS}
 *     (Lucene: 25)
 * @param boost whether each term is boosted by its score, relative to the weakest term's
 * @param analyzer analyzes the stored strings of fields without a term vector
 */
public record MoreLikeThisQuery(
        int docId,
        List<String> fields,
        int minTermFreq,
        int minDocFreq,
        OptionalInt maxDocFreq,
        int maxQueryTerms,
        boolean boost,
        Analyzer analyzer) {

    /** The largest {@code maxQueryTerms} the server accepts: Lucene's maximum clause count. */
    public static final int MAX_QUERY_TERMS = 1024;
}
