package io.github.mfuerstenau.leia.runtime;

import java.util.Map;

/**
 * Lucene's standard (flexible) query parser, as leia-core's {@code Queries.standard} runs it on
 * Lucene 10 (ADR 0011, ADR 0066), without dates: range bounds stay text, as in {@link Search#parse}.
 */
public interface StandardSearch {

    /**
     * Parses {@code expression} with the standard query parser and {@code analyzer}. A range or a
     * value on a field of {@code points} becomes a point query for values of its type; other fields
     * are text. A fuzzy term without a distance allows 2 edits.
     *
     * @param points the point fields, each with one-dimensional points as wide as its type
     * @throws Search.InvalidQueryException if the expression doesn't parse, or a point value is not a
     *     number; the message says where, as the classic parser's does
     * @throws Search.UnsupportedAnalyzerException if the runtime can't build {@code analyzer}
     */
    Search.ParsedQuery parse(
            String expression, Options options, Map<String, PointType> points, Search.AnalyzerSpec analyzer)
            throws Search.InvalidQueryException, Search.UnsupportedAnalyzerException;

    /** The standard query parser's options: those of the classic one but auto-generated phrases. */
    record Options(
            String defaultField,
            boolean defaultAnd,
            boolean allowLeadingWildcard,
            int phraseSlop,
            int fuzzyPrefixLength,
            boolean enablePositionIncrements) {}

    /** The type of a point field's values, which the index doesn't record. */
    enum PointType {
        INT,
        LONG,
        FLOAT,
        DOUBLE
    }
}
