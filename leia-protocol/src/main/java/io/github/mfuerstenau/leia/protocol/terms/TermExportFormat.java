package io.github.mfuerstenau.leia.protocol.terms;

/** The line layout of a term export (ADR 0022, ADR 0094). */
public enum TermExportFormat {
    /** Luke's layout: {@code term<delimiter>docFreq}. A term that isn't text is written as hex. */
    LUKE,
    /**
     * {@code marker<delimiter>docFreq<delimiter>totalTermFreq<delimiter>term}, where {@code marker} is
     * {@code t} for a term written as text and {@code x} for one written as lowercase hex. The term
     * comes last, so a reader splits at the first three delimiters.
     */
    FULL
}
