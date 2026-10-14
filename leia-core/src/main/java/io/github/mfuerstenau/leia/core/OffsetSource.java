package io.github.mfuerstenau.leia.core;

/**
 * Where the highlighter took the offsets of the matches in a field from (ADR 0042), as Lucene's
 * {@code UnifiedHighlighter.OffsetSource}.
 */
public enum OffsetSource {
    /** The postings, indexed with offsets. */
    POSTINGS,
    /** The document's term vector, with offsets. */
    TERM_VECTORS,
    /** The postings, and the term vector for the terms of a multi-term query. */
    POSTINGS_WITH_TERM_VECTORS,
    /** The value, analyzed again. */
    ANALYSIS,
    /** None: the query has no terms in the field. */
    NONE
}
