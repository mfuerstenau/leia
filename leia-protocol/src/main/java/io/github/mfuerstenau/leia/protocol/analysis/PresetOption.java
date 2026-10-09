package io.github.mfuerstenau.leia.protocol.analysis;

/** What a preset analyzer takes besides its word sets (ADR 0023); serialized as its name. */
public enum PresetOption {
    /** {@code maxTokenLength}. */
    MAX_TOKEN_LENGTH,
    /** {@code stemOverride}. */
    STEM_OVERRIDE,
    /** {@code japanese}. */
    JAPANESE,
    /** {@code korean}. */
    KOREAN
}
