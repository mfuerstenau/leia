package io.github.mfuerstenau.leia.protocol.analysis;

/** Kuromoji's {@code JapaneseCompletionFilter.Mode} (ADR 0038). */
public enum JapaneseCompletionMode {
    /** Simple romanization, for indexing. */
    INDEX,
    /** Romanization that knows input methods, for queries: such as half-typed romaji. */
    QUERY
}
