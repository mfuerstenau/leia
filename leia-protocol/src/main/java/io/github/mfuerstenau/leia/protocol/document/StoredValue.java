package io.github.mfuerstenau.leia.protocol.document;

import java.util.OptionalLong;

/**
 * A stored value.
 *
 * @param type the type it was stored as
 * @param value {@code STRING}: the text; {@code BINARY}: the bytes as text if they are valid UTF-8,
 *     otherwise in lowercase hex; numbers: in decimal as Java's {@code toString} writes them, so
 *     {@code NaN}, {@code Infinity} and {@code 1.0E10} can occur
 * @param binary whether {@code value} holds the bytes of a {@code BINARY} value as hex
 * @param fullLength present if {@code value} is cut (ADR 0008): the length of the whole value in
 *     bytes, UTF-8 for text. {@code value} then holds its first {@link
 *     GetDocumentResult#MAX_VALUE_LENGTH} bytes or fewer, cut between two characters, and {@code
 *     getStoredValue} fetches the whole value.
 */
public record StoredValue(StoredType type, String value, boolean binary, OptionalLong fullLength) {

    /** A value that is not cut. */
    public StoredValue(StoredType type, String value, boolean binary) {
        this(type, value, binary, OptionalLong.empty());
    }
}
