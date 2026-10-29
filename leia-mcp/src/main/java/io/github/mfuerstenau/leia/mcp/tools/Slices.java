package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.ToolError;
import io.github.mfuerstenau.leia.mcp.ToolException;
import java.util.OptionalInt;

/**
 * Slices of values and checks of limits (ADR 0100 §6). A length counts the characters of the
 * value's text, as Java's {@code String} has them; a slice never splits a surrogate pair, nor a
 * byte of a binary value in hex.
 */
final class Slices {

    private Slices() {}

    /**
     * Where the slice of {@code value} from {@code offset}, of at most {@code length} characters,
     * ends. It is one character longer only where a shorter one would be empty.
     */
    static int end(String value, boolean hex, int offset, int length) {
        int end = (int) Math.min((long) offset + length, value.length());
        boolean split = end < value.length()
                && end > offset
                && (hex ? (end - offset) % 2 != 0 : Character.isHighSurrogate(value.charAt(end - 1)));
        if (split) {
            end = end - 1 > offset ? end - 1 : end + 1;
        }
        return end;
    }

    /** The first {@code length} characters of {@code value}, or all of it. */
    static String head(String value, boolean hex, int length) {
        return value.substring(0, end(value, hex, 0, length));
    }

    /**
     * {@code value} of the argument {@code name}, or {@code fallback} without it.
     *
     * @throws ToolException if it is below {@code min} or above {@code max}
     */
    static int bounded(String name, OptionalInt value, int fallback, int min, int max) throws ToolException {
        int given = value.orElse(fallback);
        if (given < min || given > max) {
            throw ToolError.invalid(name + " must be from " + min + " to " + max + ", not " + given);
        }
        return given;
    }
}
