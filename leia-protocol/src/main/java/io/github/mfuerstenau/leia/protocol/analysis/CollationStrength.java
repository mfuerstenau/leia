package io.github.mfuerstenau.leia.protocol.analysis;

/** The strength of a collator (ADR 0038): which differences between characters count. */
public enum CollationStrength {
    /** Only base letters, such as {@code a} and {@code b}. */
    PRIMARY,
    /** Also accents, such as {@code a} and {@code ä}. */
    SECONDARY,
    /** Also case, such as {@code a} and {@code A}. */
    TERTIARY,
    /** Also punctuation, when it is ignored at the other levels; ICU's collator only. */
    QUATERNARY,
    /** Every difference. */
    IDENTICAL
}
