package io.github.mfuerstenau.leia.client.values;

import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The encodings of the raw numbers of numeric doc values, which the index doesn't record (ADR
 * 0033): a number decoded in an encoding, a stored number encoded in one, and which encoding the
 * stored values of the same field in the same document confirm.
 */
public final class DocValuesNumbers {

    private DocValuesNumbers() {}

    /** {@code number} decoded as {@code encoding}. Floats print as Rust's {@code {:?}} prints them. */
    public static String format(DocValuesNumber encoding, long number) {
        return switch (encoding) {
            case LONG -> Long.toString(number);
            case DOUBLE -> RustDebug.of(Double.longBitsToDouble(number));
            case SORTABLE_DOUBLE -> RustDebug.of(Double.longBitsToDouble(sortableDoubleBits(number)));
            case FLOAT -> RustDebug.of(Float.intBitsToFloat((int) number));
            case SORTABLE_FLOAT -> RustDebug.of(Float.intBitsToFloat(sortableFloatBits((int) number)));
        };
    }

    /** The number that {@code encoding} writes for {@code text}, a number as Java prints it. */
    public static long encode(DocValuesNumber encoding, String text) {
        return switch (encoding) {
            case LONG -> Long.parseLong(text);
            case DOUBLE -> Double.doubleToRawLongBits(Double.parseDouble(text));
            case SORTABLE_DOUBLE -> sortableDoubleBits(Double.doubleToRawLongBits(Double.parseDouble(text)));
            case FLOAT -> Float.floatToRawIntBits(Float.parseFloat(text));
            case SORTABLE_FLOAT -> sortableFloatBits(Float.floatToRawIntBits(Float.parseFloat(text)));
        };
    }

    /**
     * The encoding in which {@code stored} are exactly {@code numbers}, or empty if there is none:
     * the field stores nothing, mixes types, or holds other values. A stored double that is, bit for
     * bit, what a candidate encoding writes for these numbers settles it; without one, nothing is
     * guessed. Raw bits come before sortable ones for a {@code NUMERIC} field, as {@code
     * DoubleDocValuesField} writes it, and the other way round for {@code SORTED_NUMERIC}, as {@code
     * DoubleField} does; they name the same encoding for a positive number.
     */
    public static Optional<DocValuesNumber> inferFromStored(
            DocValuesType type, List<Long> numbers, List<StoredValue> stored) {
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
                    .mapToLong(value -> encode(candidate, value.value()))
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

    /** Lucene's {@code NumericUtils.sortableDoubleBits}, its own inverse. */
    private static long sortableDoubleBits(long bits) {
        return bits ^ ((bits >> 63) & Long.MAX_VALUE);
    }

    /** Lucene's {@code NumericUtils.sortableFloatBits}, its own inverse. */
    private static int sortableFloatBits(int bits) {
        return bits ^ ((bits >> 31) & Integer.MAX_VALUE);
    }
}
