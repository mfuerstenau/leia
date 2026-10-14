package io.github.mfuerstenau.leia.protocol.search;

import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;

/**
 * How the matches of a query are highlighted in stored text, with Lucene's {@code
 * UnifiedHighlighter} (ADR 0042).
 *
 * @param maxPassages the most passages a hit has per field, from 1 to {@link #MAX_PASSAGES}; {@code
 *     startHighlight} ignores it and marks every match
 * @param requireFieldMatch whether a field is highlighted only with the terms the query has in that
 *     field; otherwise with the query's terms of every field
 * @param analyzer analyzes a value where the offsets of its terms come from analysis, not from the
 *     postings or the term vector
 */
public record HighlightOptions(int maxPassages, boolean requireFieldMatch, Analyzer analyzer) {

    /** The most passages per field that a hit can ask for. */
    public static final int MAX_PASSAGES = 10;

    /** The most characters of a passage's text; a longer passage is cut around its first match. */
    public static final int MAX_PASSAGE_CHARS = 300;

    /** How many characters of a field a hit's passages are found in; later matches are not seen. */
    public static final int MAX_HIT_CHARS = 100_000;

    /**
     * How many characters of a field {@code startHighlight} marks matches in; later matches are not
     * seen.
     */
    public static final int MAX_DOCUMENT_CHARS = 1 << 20;
}
