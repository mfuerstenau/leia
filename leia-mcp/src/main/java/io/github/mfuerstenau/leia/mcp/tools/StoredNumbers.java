package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Which encoding the raw numbers of a numeric doc values field have, told by the stored values of
 * the same field in the same document: the index doesn't record it (ADR 0033), but a stored
 * double that is, bit for bit, what a candidate encoding writes for these numbers settles it.
 * Without a stored value to confirm it, nothing is guessed.
 */
final class StoredNumbers {

    private StoredNumbers() {}

    /**
     * The encoding in which {@code stored} are exactly {@code numbers}, or empty if there is none:
     * the field stores nothing, mixes types, or holds other values. Raw bits come before sortable
     * ones for a {@code NUMERIC} field, as {@code DoubleDocValuesField} writes it, and the other
     * way round for {@code SORTED_NUMERIC}, as {@code DoubleField} does; they name the same
     * encoding for a positive number.
     */
    static Optional<DocValuesNumber> encoding(DocValuesType type, List<Long> numbers, List<StoredValue> stored) {
        if (numbers.isEmpty() || numbers.size() != stored.size()) {
            return Optional.empty();
        }
        boolean integers = stored.stream().allMatch(value -> isInteger(value.type()));
        boolean doubles = stored.stream().allMatch(value -> value.type() == StoredType.DOUBLE);
        boolean floats = stored.stream().allMatch(value -> value.type() == StoredType.FLOAT);
        boolean sortedFirst = type == DocValuesType.SORTED_NUMERIC;
        List<DocValuesNumber> candidates;
        if (integers) {
            candidates = List.of(DocValuesNumber.LONG);
        } else if (doubles) {
            candidates = sortedFirst
                    ? List.of(DocValuesNumber.SORTABLE_DOUBLE, DocValuesNumber.DOUBLE)
                    : List.of(DocValuesNumber.DOUBLE, DocValuesNumber.SORTABLE_DOUBLE);
        } else if (floats) {
            candidates = sortedFirst
                    ? List.of(DocValuesNumber.SORTABLE_FLOAT, DocValuesNumber.FLOAT)
                    : List.of(DocValuesNumber.FLOAT, DocValuesNumber.SORTABLE_FLOAT);
        } else {
            return Optional.empty();
        }
        long[] actual = numbers.stream().mapToLong(Long::longValue).sorted().toArray();
        for (DocValuesNumber candidate : candidates) {
            long[] expected = stored.stream()
                    .mapToLong(value -> written(candidate, value.value()))
                    .sorted()
                    .toArray();
            if (Arrays.equals(actual, expected)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean isInteger(StoredType type) {
        return type == StoredType.INT || type == StoredType.LONG;
    }

    /** The number that {@code encoding} writes for {@code text}, a stored number as Java prints it. */
    private static long written(DocValuesNumber encoding, String text) {
        return switch (encoding) {
            case LONG -> Long.parseLong(text);
            case DOUBLE -> Double.doubleToRawLongBits(Double.parseDouble(text));
            case SORTABLE_DOUBLE -> {
                long bits = Double.doubleToRawLongBits(Double.parseDouble(text));
                yield bits ^ ((bits >> 63) & Long.MAX_VALUE);
            }
            case FLOAT -> Float.floatToRawIntBits(Float.parseFloat(text));
            case SORTABLE_FLOAT -> {
                int bits = Float.floatToRawIntBits(Float.parseFloat(text));
                yield bits ^ ((bits >> 31) & Integer.MAX_VALUE);
            }
        };
    }
}
