package io.github.mfuerstenau.leia.protocol.document;

/**
 * Result of {@code getStoredValue}.
 *
 * @param value the whole stored value, never cut
 */
public record GetStoredValueResult(StoredValue value) {}
