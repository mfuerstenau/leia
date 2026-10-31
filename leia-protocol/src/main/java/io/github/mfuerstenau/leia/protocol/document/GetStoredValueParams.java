package io.github.mfuerstenau.leia.protocol.document;

import java.util.OptionalInt;

/**
 * Params of {@code getStoredValue}.
 *
 * @param docId the document id, from 0 to maxDoc - 1
 * @param field the field
 * @param index which of the document's stored values in the field, from 0, in the order {@code
 *     getDocument} lists them
 * @param offset where the slice starts, in characters of the value as the protocol carries it: UTF-16
 *     chars of a text, hex digits of a binary value in hex, where it must be even; 0 if absent
 * @param length the most characters of the slice, or absent for all up to the end of the value. A
 *     slice never splits a surrogate pair or a byte in hex, so it is one character longer where a
 *     shorter one would be empty; 0 gives an empty slice
 */
public record GetStoredValueParams(int docId, String field, int index, OptionalInt offset, OptionalInt length) {

    /** The whole value. */
    public GetStoredValueParams(int docId, String field, int index) {
        this(docId, field, index, OptionalInt.empty(), OptionalInt.empty());
    }
}
