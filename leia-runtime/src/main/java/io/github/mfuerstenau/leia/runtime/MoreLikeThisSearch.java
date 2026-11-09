package io.github.mfuerstenau.leia.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Documents like one document of the index, or like a text, by the terms that Lucene's {@code
 * MoreLikeThis} picks, as leia-core's {@code Queries.moreLikeThis} and {@code moreLikeText} build
 * them on Lucene 10 (ADR 0013, ADR 0028, ADR 0066).
 *
 * <p>A query of {@link #like} only holds the source, the options and the analyzer's spec: wherever it
 * is used ({@link Search#search}, {@link Search#matches}, {@link Search#explain}, {@link Scoring} and
 * {@link Highlight}), the runtime builds the analyzer, then the {@code MoreLikeThis} query from the
 * reader it uses, and closes the analyzer afterwards. Its {@code toString} names the source and the
 * options, as leia-core's does. A search with it returns the terms it chose in {@link
 * Search.SearchResults#likeTerms()}.
 */
public interface MoreLikeThisSearch {

    /**
     * The query for documents like {@code source}. The analyzer is built once here, so that one the
     * runtime can't build fails right away.
     *
     * @param analyzer analyzes the text, or the stored strings of fields without a term vector
     * @throws Search.UnsupportedAnalyzerException if the runtime can't build {@code analyzer}
     * @throws Search.InvalidAnalyzerException if {@code analyzer} is wrong
     */
    Search.ParsedQuery like(Source source, Options options, Search.AnalyzerSpec analyzer)
            throws Search.UnsupportedAnalyzerException, Search.InvalidAnalyzerException;

    /** Where the terms come from. */
    sealed interface Source {

        /** The terms of a document of the index, which may be deleted; the caller checks that it exists. */
        record Doc(int docId) implements Source {}

        /** The terms of a text, analyzed for each of the fields. */
        record Text(String text) implements Source {
            public Text {
                Objects.requireNonNull(text);
            }
        }
    }

    /**
     * How {@code MoreLikeThis} picks the terms.
     *
     * @param fields the fields to take terms from, at least one and none twice
     * @param minTermFreq terms that occur fewer times in the document are left out
     * @param minDocFreq terms that occur in fewer documents are left out
     * @param maxDocFreq terms that occur in more documents are left out; empty for no limit
     * @param maxQueryTerms how many of the best terms the query has, from 1 to {@link
     *     #MAX_QUERY_TERMS}
     * @param boost whether each term is boosted by its score, relative to the weakest term's
     * @param minWordLen terms shorter than this, in UTF-16 units, are left out; 0 for no limit
     * @param maxWordLen terms longer than this, in UTF-16 units, are left out; empty for no limit
     * @param stopWords terms that are left out, compared exactly with the analyzed terms
     */
    record Options(
            List<String> fields,
            int minTermFreq,
            int minDocFreq,
            @Nullable Integer maxDocFreq,
            int maxQueryTerms,
            boolean boost,
            int minWordLen,
            @Nullable Integer maxWordLen,
            Set<String> stopWords) {

        /** The most terms of a query: Lucene's default maximum clause count. */
        public static final int MAX_QUERY_TERMS = 1024;

        /**
         * With leia-core's checks and messages.
         *
         * @throws IllegalArgumentException for no field, a field listed twice, or a number out of range
         */
        public Options {
            fields = List.copyOf(fields);
            stopWords = Set.copyOf(stopWords);
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
            if (maxDocFreq != null && maxDocFreq < 0) {
                throw new IllegalArgumentException("maxDocFreq must be 0 or more, not " + maxDocFreq);
            }
            if (maxQueryTerms < 1 || maxQueryTerms > MAX_QUERY_TERMS) {
                throw new IllegalArgumentException(
                        "maxQueryTerms must be from 1 to " + MAX_QUERY_TERMS + ", not " + maxQueryTerms);
            }
            if (minWordLen < 0) {
                throw new IllegalArgumentException("minWordLen must be 0 or more, not " + minWordLen);
            }
            if (maxWordLen != null && maxWordLen < Math.max(1, minWordLen)) {
                throw new IllegalArgumentException(
                        "maxWordLen must be 1 or more and at least minWordLen (" + minWordLen + "), not " + maxWordLen);
            }
        }

        /** Whether {@code MoreLikeThis} leaves {@code term} out by its length or as a stop word. */
        public boolean isNoiseWord(String term) {
            int length = term.length();
            if (minWordLen > 0 && length < minWordLen) {
                return true;
            }
            if (maxWordLen != null && length > maxWordLen) {
                return true;
            }
            return stopWords.contains(term);
        }
    }

    /**
     * A term that {@code MoreLikeThis} chose, with the numbers it chose it by, worked out again with
     * its rules as leia-core does (ADR 0028).
     *
     * @param tf how often it occurs in the source
     * @param docFreq in how many documents it occurs
     * @param idf its TF-IDF inverse document frequency
     * @param score {@code tf * idf}, by which the terms are ranked
     */
    record LikeTerm(String field, String term, int tf, int docFreq, float idf, float score) {}
}
