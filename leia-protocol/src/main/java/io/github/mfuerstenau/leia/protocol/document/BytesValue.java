package io.github.mfuerstenau.leia.protocol.document;

/**
 * A byte string.
 *
 * @param value the bytes as text; if {@code binary}, in lowercase hex instead
 * @param binary whether the bytes are not valid UTF-8, so {@code value} holds them as hex
 */
public record BytesValue(String value, boolean binary) {}
