package io.github.mfuerstenau.leia.protocol.analysis;

/** Nori's {@code KoreanTokenizer.DecompoundMode} (ADR 0023); serialized as its name. */
public enum KoreanDecompoundMode {
    /** Compounds are kept whole. */
    NONE,
    /** Compounds are split, and the compound is dropped. */
    DISCARD,
    /** Compounds are split, and the compound is kept too. */
    MIXED
}
