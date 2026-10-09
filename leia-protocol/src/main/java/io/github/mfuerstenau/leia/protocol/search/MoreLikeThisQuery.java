package io.github.mfuerstenau.leia.protocol.search;

import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Documents like one document of the index, or like a text, by the terms that Lucene's {@code
 * MoreLikeThis} picks from it (ADR 0013, ADR 0028). Exactly one of {@code docId} and {@code text} is
 * present. A document's terms come, per field, from its term vector, or else from its stored
 * strings, analyzed with {@code analyzer} (ADR 0014); a text is analyzed for each field.
 *
 * @param docId the source document, from 0 to maxDoc - 1; it may be deleted
 * @param text the source text, at most {@code MAX_TEXT_LENGTH} bytes in UTF-8
 * @param fields the indexed fields to take terms from, at least one and none twice
 * @param minTermFreq terms that occur fewer times in the document are left out (Lucene: 2)
 * @param minDocFreq terms that occur in fewer documents are left out (Lucene: 5)
 * @param maxDocFreq terms that occur in more documents are left out; absent for no limit
 * @param maxQueryTerms how many of the best terms the query has, from 1 to {@code MAX_QUERY_TERMS}
 *     (Lucene: 25)
 * @param boost whether each term is boosted by its score, relative to the weakest term's
 * @param analyzer analyzes the text, or the stored strings of fields without a term vector
 * @param minWordLen terms shorter than this, in UTF-16 units, are left out; 0 for no limit
 * @param maxWordLen terms longer than this, in UTF-16 units, are left out; absent for no limit,
 *     else at least 1 and at least {@code minWordLen}
 * @param stopWords terms that are left out, compared exactly with the analyzed terms
 */
public record MoreLikeThisQuery(
        OptionalInt docId,
        Optional<String> text,
        List<String> fields,
        int minTermFreq,
        int minDocFreq,
        OptionalInt maxDocFreq,
        int maxQueryTerms,
        boolean boost,
        Analyzer analyzer,
        int minWordLen,
        OptionalInt maxWordLen,
        List<String> stopWords) {

    /** The largest {@code maxQueryTerms} the server accepts: Lucene's maximum clause count. */
    public static final int MAX_QUERY_TERMS = 1024;

    /** The longest {@code text} the server accepts, in bytes of UTF-8. */
    public static final int MAX_TEXT_LENGTH = 65_536;
}
