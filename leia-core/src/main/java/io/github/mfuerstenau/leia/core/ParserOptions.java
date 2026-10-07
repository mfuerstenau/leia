package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.Queries.Operator;

/**
 * The options that the classic and the standard query parser share (ADR 0011).
 *
 * @param defaultOperator how clauses without an operator are combined
 * @param allowLeadingWildcard whether a term may start with {@code *} or {@code ?}
 * @param phraseSlop the slop of phrases that give none
 * @param fuzzyPrefixLength how many leading characters of a fuzzy term must match exactly
 * @param enablePositionIncrements whether phrases keep the gaps that the analyzer leaves
 */
public record ParserOptions(
        Operator defaultOperator,
        boolean allowLeadingWildcard,
        int phraseSlop,
        int fuzzyPrefixLength,
        boolean enablePositionIncrements) {

    /** @throws IllegalArgumentException if a number is out of range */
    public ParserOptions {
        if (phraseSlop < 0) {
            throw new IllegalArgumentException("phraseSlop must be 0 or more, not " + phraseSlop);
        }
        if (fuzzyPrefixLength < 0) {
            throw new IllegalArgumentException("fuzzyPrefixLength must be 0 or more, not " + fuzzyPrefixLength);
        }
    }

    /** Lucene's defaults, with {@code defaultOperator}. */
    public static ParserOptions defaults(Operator defaultOperator) {
        return new ParserOptions(defaultOperator, false, 0, 0, true);
    }
}
