package io.github.mfuerstenau.leia.core;

/** What a preset analyzer takes besides its word sets (ADR 0023). */
public enum PresetOption {
    /** The longest token, through {@code setMaxTokenLength(int)} or {@code WhitespaceAnalyzer(int)}. */
    MAX_TOKEN_LENGTH,
    /** A stem override dictionary, through a {@code (CharArraySet, CharArraySet, CharArrayMap)} constructor. */
    STEM_OVERRIDE,
    /** The options of Kuromoji's {@code JapaneseAnalyzer}. */
    JAPANESE,
    /** The options of Nori's {@code KoreanAnalyzer}. */
    KOREAN
}
