package io.github.mfuerstenau.leia.protocol.analysis;

/** Kuromoji's {@code JapaneseTokenizer.Mode} (ADR 0023); serialized as its name. */
public enum JapaneseMode {
    /** Ordinary segmentation, without decompounding. */
    NORMAL,
    /** Segmentation for search: long compounds are also split into their parts. */
    SEARCH,
    /** Like {@code SEARCH}, and unknown words are split into unigrams. */
    EXTENDED
}
