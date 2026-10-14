package io.github.mfuerstenau.leia.protocol.search;

/** Where the highlighter took the offsets of the matches in a field from (ADR 0042). */
public enum OffsetSource {
    /** The postings, indexed with offsets. */
    POSTINGS,
    /** The term vector of the document, with offsets. */
    TERM_VECTORS,
    /** The postings, and the term vector for the terms of a wildcard, fuzzy or other query. */
    POSTINGS_WITH_TERM_VECTORS,
    /** The value, analyzed again with the analyzer of the highlight options. */
    ANALYSIS,
    /** None: the query has no terms in the field. */
    NONE
}
