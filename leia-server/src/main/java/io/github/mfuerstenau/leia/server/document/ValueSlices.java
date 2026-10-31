package io.github.mfuerstenau.leia.server.document;

/**
 * Where the slice of a stored value ends (ADR 0117). A slice never splits a surrogate pair, nor a
 * byte of a binary value in hex.
 */
public final class ValueSlices {

    private ValueSlices() {}

    /**
     * Where the slice of {@code value} from {@code offset}, of at most {@code length} characters,
     * ends. It is one character longer only where a shorter one would be empty.
     */
    public static int end(String value, boolean hex, int offset, int length) {
        int end = (int) Math.min((long) offset + length, value.length());
        boolean split = end < value.length()
                && end > offset
                && (hex ? (end - offset) % 2 != 0 : Character.isHighSurrogate(value.charAt(end - 1)));
        if (split) {
            end = end - 1 > offset ? end - 1 : end + 1;
        }
        return end;
    }
}
