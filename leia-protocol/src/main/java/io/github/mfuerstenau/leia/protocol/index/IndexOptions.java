package io.github.mfuerstenau.leia.protocol.index;

/** What the postings of a field hold, as Lucene's {@code IndexOptions}; serialized as its name. */
public enum IndexOptions {
    NONE,
    DOCS,
    DOCS_AND_FREQS,
    DOCS_AND_FREQS_AND_POSITIONS,
    DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS,
    /** Like {@code DOCS_AND_FREQS}, but the frequencies are custom values such as scores, not term counts. */
    DOCS_AND_CUSTOM_FREQS
}
