package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongConsumer;

/** The classic query parser and search of a {@link RuntimeIndex}. */
public interface Search {

    /** Parses {@code expression} with the classic query parser and the runtime's standard analyzer. */
    ParsedQuery parse(String expression, ParserOptions options) throws InvalidQueryException;

    /**
     * Searches with a query from {@link #parse}, best score first.
     *
     * @param fields the stored fields to return per hit; empty for all of them
     * @param progress receives the documents searched so far, from time to time; it may throw to stop
     *     the search
     */
    SearchResults search(ParsedQuery query, int offset, int limit, Optional<List<String>> fields, LongConsumer progress)
            throws IOException;

    /** A query from {@link #parse}; its {@code toString} is the parsed query. */
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

    /** A query that the classic query parser can't parse. */
    final class InvalidQueryException extends Exception {
        private static final long serialVersionUID = 1L;

        public InvalidQueryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A hit, with the stored values of the fields asked for. */
    record Hit(int docId, float score, Map<String, List<RuntimeIndex.StoredValue>> fields) {}

    record SearchResults(String query, String rewrittenQuery, long totalHits, List<Hit> hits, long tookMillis) {}
}
