package io.github.mfuerstenau.leia.protocol.document;

/**
 * Result of {@code getStoredValue}.
 *
 * @param value the slice of the stored value that was asked for, the whole value without an offset and
 *     a length; its {@code fullLength} stays empty
 * @param offset where the slice starts, in characters
 * @param totalLength the length in characters of the whole value as the protocol carries it
 */
public record GetStoredValueResult(StoredValue value, int offset, int totalLength) {

    /** The whole {@code value}. */
    public GetStoredValueResult(StoredValue value) {
        this(value, 0, value.value().length());
    }
}
