package io.github.mfuerstenau.leia.protocol.search;

/**
 * The options that the classic and the standard query parser share (ADR 0011).
 *
 * @param allowLeadingWildcard whether a term may start with {@code *} or {@code ?}
 * @param phraseSlop the slop of phrases that give none, 0 or more
 * @param fuzzyPrefixLength how many leading characters of a fuzzy term must match exactly, 0 or more
 * @param enablePositionIncrements whether phrases keep the gaps that the analyzer leaves
 */
public record ParserOptions(
        boolean allowLeadingWildcard, int phraseSlop, int fuzzyPrefixLength, boolean enablePositionIncrements) {}
