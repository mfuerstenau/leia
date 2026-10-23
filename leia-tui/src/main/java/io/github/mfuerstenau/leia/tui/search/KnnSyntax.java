package io.github.mfuerstenau.leia.tui.search;

import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The query line of the search screen's KNN mode (ADR 0040): the id of the document whose vector is
 * the target, or the target itself, in brackets.
 *
 * <pre>
 * 42          [0.5, -1, 2.25]          [12 -7 0 3]
 * </pre>
 *
 * <p>The components of a vector are separated by commas, whitespace or both. A field of floats takes
 * decimal numbers, with an exponent if need be; a field of bytes takes whole numbers from -128 to
 * 127. A line that can't be read throws an {@link IllegalArgumentException} with the message to show.
 */
public final class KnnSyntax {

    private KnnSyntax() {}

    /** What the line asks for. */
    public sealed interface Target {

        /** The vector of this document. */
        record Doc(int docId) implements Target {}

        /** This vector. */
        record Vector(KnnVector vector) implements Target {}
    }

    /** A decimal number, as Java writes a float, without NaN, the infinities, hex or a type suffix. */
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    private static final Pattern WHOLE = Pattern.compile("[+-]?\\d+");

    private static final Pattern SEPARATORS = Pattern.compile("[\\s,]+");

    /**
     * The target of {@code line}, for a field of {@code encoding}.
     *
     * @throws IllegalArgumentException if the line is neither a document id nor a vector of the
     *     encoding
     */
    public static Target target(String line, VectorEncoding encoding) {
        String text = line.strip();
        if (text.startsWith("[")) {
            if (!text.endsWith("]") || text.length() < 2) {
                throw new IllegalArgumentException("A vector ends with ].");
            }
            return new Target.Vector(vector(text.substring(1, text.length() - 1), encoding));
        }
        if (text.chars().allMatch(c -> c >= '0' && c <= '9') && !text.isEmpty()) {
            try {
                return new Target.Doc(Integer.parseInt(text));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + text + "' is too large for a document id.", e);
            }
        }
        throw new IllegalArgumentException(
                "Type a document id, or a vector in brackets such as " + example(encoding) + ".");
    }

    private static KnnVector vector(String components, VectorEncoding encoding) {
        List<String> parts = new ArrayList<>();
        for (String part : SEPARATORS.split(components.strip())) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("The vector has no components.");
        }
        return switch (encoding) {
            case FLOAT32 -> new KnnVector(VectorEncoding.FLOAT32, floats(parts), List.of());
            case BYTE -> new KnnVector(VectorEncoding.BYTE, List.of(), bytes(parts));
        };
    }

    private static List<Float> floats(List<String> parts) {
        List<Float> floats = new ArrayList<>(parts.size());
        for (String part : parts) {
            float value = DECIMAL.matcher(part).matches() ? Float.parseFloat(part) : Float.NaN;
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("'" + part + "' is not a finite decimal number.");
            }
            floats.add(value);
        }
        return List.copyOf(floats);
    }

    private static List<Byte> bytes(List<String> parts) {
        List<Byte> bytes = new ArrayList<>(parts.size());
        for (String part : parts) {
            int value = WHOLE.matcher(part).matches() && part.length() <= 5 ? Integer.parseInt(part) : 1000;
            if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "'" + part + "' is not a byte: a vector of bytes takes whole numbers from -128 to 127.");
            }
            bytes.add((byte) value);
        }
        return List.copyOf(bytes);
    }

    private static String example(VectorEncoding encoding) {
        return switch (encoding) {
            case FLOAT32 -> "[0.5, -1, 2.25]";
            case BYTE -> "[12, -7, 0]";
        };
    }
}
