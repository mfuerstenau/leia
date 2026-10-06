package io.github.mfuerstenau.leia.protocol.document;

/**
 * A stored value.
 *
 * @param type the type it was stored as
 * @param value {@code STRING}: the text; {@code BINARY}: the bytes as text if they are valid UTF-8,
 *     otherwise in lowercase hex; numbers: in decimal as Java's {@code toString} writes them, so
 *     {@code NaN}, {@code Infinity} and {@code 1.0E10} can occur
 * @param binary whether {@code value} holds the bytes of a {@code BINARY} value as hex
 */
public record StoredValue(StoredType type, String value, boolean binary) {}
