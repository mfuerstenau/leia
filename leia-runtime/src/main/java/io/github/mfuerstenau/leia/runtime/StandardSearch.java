package io.github.mfuerstenau.leia.runtime;

import java.util.Map;
import java.util.Objects;

/**
 * Lucene's standard (flexible) query parser, as leia-core's {@code Queries.standard} runs it on
 * Lucene 10 (ADR 0011, ADR 0066), with the dates of range bounds (ADR 0069).
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
     * @throws Search.InvalidAnalyzerException if {@code analyzer} is wrong
     */
    Search.ParsedQuery parse(
            String expression, Options options, Map<String, PointType> points, Search.AnalyzerSpec analyzer)
            throws Search.InvalidQueryException, Search.UnsupportedAnalyzerException, Search.InvalidAnalyzerException;

    /**
     * The standard query parser's options: those of the classic one but auto-generated phrases, fuzzy
     * edits and splitting on whitespace.
     *
     * @param dates how range bounds on fields that aren't in {@code points} are read as dates (ADR 0069)
     */
    record Options(
            String defaultField,
            boolean defaultAnd,
            boolean allowLeadingWildcard,
            int phraseSlop,
            int fuzzyPrefixLength,
            boolean enablePositionIncrements,
            Search.Dates dates) {

        public Options {
            Objects.requireNonNull(dates);
        }

        /** Options without dates. */
        public Options(
                String defaultField,
                boolean defaultAnd,
                boolean allowLeadingWildcard,
                int phraseSlop,
                int fuzzyPrefixLength,
                boolean enablePositionIncrements) {
            this(
                    defaultField,
                    defaultAnd,
                    allowLeadingWildcard,
                    phraseSlop,
                    fuzzyPrefixLength,
                    enablePositionIncrements,
                    Search.Dates.NONE);
        }
    }

    /** The type of a point field's values, which the index doesn't record. */
    enum PointType {
        INT,
        LONG,
        FLOAT,
        DOUBLE
    }
}
