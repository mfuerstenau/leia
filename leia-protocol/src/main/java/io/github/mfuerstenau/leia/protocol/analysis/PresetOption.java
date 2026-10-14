package io.github.mfuerstenau.leia.protocol.analysis;

/** What a preset analyzer takes besides its word sets (ADR 0023). */
public enum PresetOption {
    /** {@code maxTokenLength}. */
    MAX_TOKEN_LENGTH,
    /** {@code stemOverride}. */
    STEM_OVERRIDE,
    /** {@code japanese}. */
    JAPANESE,
    /** {@code korean}. */
    KOREAN,
    /** {@code collation} (ADR 0038). */
    COLLATION,
    /** {@code japaneseCompletion} (ADR 0038). */
    JAPANESE_COMPLETION
}
