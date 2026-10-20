package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.LongConsumer;

/** The classic query parser, term queries and search of a {@link RuntimeIndex}. */
public interface Search {

    /** The simple class names of the analyzers that {@link AnalyzerSpec.Named} can name. */
    Set<String> analyzers();

    /**
     * Parses {@code expression} with the classic query parser and {@code analyzer}.
     *
     * @throws UnsupportedAnalyzerException if the runtime can't build {@code analyzer}
     */
    ParsedQuery parse(String expression, ParserOptions options, AnalyzerSpec analyzer)
            throws InvalidQueryException, UnsupportedAnalyzerException;

    /**
     * A query for exactly one term of {@code field}, neither analyzed nor split (ADR 0058), for
     * {@link #search}, {@link #matches} and {@link #explain}.
     */
    ParsedQuery term(String field, byte[] term);

    /**
     * Searches with a query from {@link #parse}, best score first.
     *
     * @param fields the stored fields to return per hit; empty for all of them
     * @param progress receives the documents searched so far, from time to time; it may throw to stop
     *     the search
     */
    SearchResults search(ParsedQuery query, int offset, int limit, Optional<List<String>> fields, LongConsumer progress)
            throws IOException;

    /**
     * Every document that matches a query from {@link #parse}, unscored, for a filter (ADR 0056):
     * the live ones, and the deleted ones too if {@code includeDeleted}.
     *
     * @param progress receives the documents searched so far, from time to time; it may throw to stop
     *     the matching
     * @throws IllegalArgumentException if {@code includeDeleted} and not {@link #matchesDeleted()}
     */
    Matches matches(ParsedQuery query, boolean includeDeleted, LongConsumer progress) throws IOException;

    /**
     * Whether {@link #matches} takes {@code includeDeleted}: false if the runtime's Lucene can't read
     * the postings of deleted documents, as 3.6 can't.
     */
    boolean matchesDeleted();

    /**
     * How a query from {@link #parse} or {@link Knn} scores document {@code docId} (ADR 0057), with
     * the runtime's default similarity, as {@link #search} scores it. A deleted document doesn't
     * match.
     *
     * @param progress receives the documents searched so far, from time to time; it may throw to stop
     *     the explanation
     */
    Explanation explain(ParsedQuery query, int docId, LongConsumer progress) throws IOException;

    /**
     * A query from {@link #parse}, {@link #term}, {@link Knn}, {@link DocValuesSearch}, {@link
     * StandardSearch} or {@link MoreLikeThisSearch}; its {@code toString} is the parsed query.
     */
    interface ParsedQuery {}

    /** The classic query parser's options. */
    record ParserOptions(
            String defaultField,
            boolean defaultAnd,
            boolean allowLeadingWildcard,
            int phraseSlop,
            int fuzzyPrefixLength,
            boolean enablePositionIncrements,
            boolean autoGeneratePhraseQueries) {}

    /** An analyzer of the runtime's Lucene (ADR 0045). */
    sealed interface AnalyzerSpec {

        /**
         * An analyzer of {@link #analyzers()} by its simple class name, such as {@code
         * StandardAnalyzer}.
         *
         * @param stopwords its stop words; empty for its default ones
         * @param maxTokenLength its longest token; empty for its default
         */
        record Named(String name, Optional<List<String>> stopwords, OptionalInt maxTokenLength)
                implements AnalyzerSpec {}

        /** {@code fallback}, and for each field of {@code fields} its own analyzer. */
        record PerField(AnalyzerSpec fallback, Map<String, AnalyzerSpec> fields) implements AnalyzerSpec {}

        /** At most {@code maxTokenCount} tokens of {@code analyzer}. */
        record LimitTokenCount(AnalyzerSpec analyzer, int maxTokenCount) implements AnalyzerSpec {}

        /**
         * {@code analyzer}, whose tokens of each field in {@code words} lose those words,
         * case-sensitive, after the analyzer's whole chain, as Lucene's {@code
         * QueryAutoStopWordAnalyzer} removes them (ADR 0039, ADR 0061). A field without an entry keeps
         * every token.
         */
        record AutoStopWords(AnalyzerSpec analyzer, Map<String, List<String>> words) implements AnalyzerSpec {}
    }

    /** An analyzer the runtime can't build; the message says what it has. */
    final class UnsupportedAnalyzerException extends Exception {
        private static final long serialVersionUID = 1L;

        public UnsupportedAnalyzerException(String message) {
            super(message);
        }
    }

    /** A query that the classic query parser can't parse. */
    final class InvalidQueryException extends Exception {
        private static final long serialVersionUID = 1L;

        public InvalidQueryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * A hit, with the stored values of the fields asked for.
     *
     * @param sortValues its value of each key of a {@link Scoring#search} sort; empty without a sort
     */
    record Hit(
            int docId,
            float score,
            List<Scoring.SortValue> sortValues,
            Map<String, List<RuntimeIndex.StoredValue>> fields) {

        /** A hit of a search without a sort. */
        public Hit(int docId, float score, Map<String, List<RuntimeIndex.StoredValue>> fields) {
            this(docId, score, List.of(), fields);
        }
    }

    /**
     * The documents that match a query, by document id of the whole index.
     *
     * @param query the parsed query
     * @param rewrittenQuery the query after rewriting
     */
    record Matches(BitSet docs, String query, String rewrittenQuery, long tookMillis) {}

    /**
     * How a document is scored.
     *
     * @param value the score: a {@code Float} where the runtime's Lucene gives a {@code float}
     */
    record Explanation(boolean match, Number value, String description, List<Explanation> details) {}

    /**
     * What a search found.
     *
     * @param likeTerms for a query of {@link MoreLikeThisSearch}, the terms it chose, best first (ADR
     *     0066); empty for any other query
     */
    record SearchResults(
            String query,
            String rewrittenQuery,
            long totalHits,
            List<Hit> hits,
            long tookMillis,
            Optional<List<MoreLikeThisSearch.LikeTerm>> likeTerms) {

        /** What a search with a query other than More-Like-This found. */
        public SearchResults(String query, String rewrittenQuery, long totalHits, List<Hit> hits, long tookMillis) {
            this(query, rewrittenQuery, totalHits, hits, tookMillis, Optional.empty());
        }
    }
}
