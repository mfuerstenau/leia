package io.github.mfuerstenau.leia.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StoredNumbersTest {

    private static StoredValue stored(StoredType type, String value) {
        return new StoredValue(type, value, false);
    }

    private static long sortable(double value) {
        long bits = Double.doubleToRawLongBits(value);
        return bits ^ ((bits >> 63) & Long.MAX_VALUE);
    }

    @Test
    void takesRawBitsForNumericAndSortableForSortedNumeric() {
        long raw = Double.doubleToRawLongBits(20.51);
        List<StoredValue> values = List.of(stored(StoredType.DOUBLE, "20.51"));

        assertThat(StoredNumbers.encoding(DocValuesType.NUMERIC, List.of(raw), values))
                .contains(DocValuesNumber.DOUBLE);
        // Positive numbers are the same in both encodings; the field's type picks the name.
        assertThat(StoredNumbers.encoding(DocValuesType.SORTED_NUMERIC, List.of(raw), values))
                .contains(DocValuesNumber.SORTABLE_DOUBLE);
    }

    @Test
    void decodesANegativeDoubleOnlyInTheEncodingThatWroteIt() {
        List<StoredValue> values = List.of(stored(StoredType.DOUBLE, "-1.5"));

        assertThat(StoredNumbers.encoding(DocValuesType.NUMERIC, List.of(sortable(-1.5)), values))
                .contains(DocValuesNumber.SORTABLE_DOUBLE);
        assertThat(StoredNumbers.encoding(
                        DocValuesType.SORTED_NUMERIC, List.of(Double.doubleToRawLongBits(-1.5)), values))
                .contains(DocValuesNumber.DOUBLE);
    }

    @Test
    void decodesFloatsAndIntegers() {
        assertThat(StoredNumbers.encoding(
                        DocValuesType.NUMERIC,
                        List.of((long) Float.floatToRawIntBits(-2.5f)),
                        List.of(stored(StoredType.FLOAT, "-2.5"))))
                .contains(DocValuesNumber.FLOAT);
        assertThat(StoredNumbers.encoding(DocValuesType.NUMERIC, List.of(-7L), List.of(stored(StoredType.INT, "-7"))))
                .contains(DocValuesNumber.LONG);
    }

    @Test
    void matchesSeveralValuesWhateverTheirOrder() {
        assertThat(StoredNumbers.encoding(
                        DocValuesType.SORTED_NUMERIC,
                        List.of(1L, 5L),
                        List.of(stored(StoredType.LONG, "5"), stored(StoredType.INT, "1"))))
                .contains(DocValuesNumber.LONG);
    }

    @Test
    void matchesNaNBitForBit() {
        assertThat(StoredNumbers.encoding(
                        DocValuesType.NUMERIC,
                        List.of(Double.doubleToRawLongBits(Double.NaN)),
                        List.of(stored(StoredType.DOUBLE, "NaN"))))
                .contains(DocValuesNumber.DOUBLE);
    }

    @Test
    void guessesNothingWithoutAConfirmingStoredValue() {
        long raw = Double.doubleToRawLongBits(20.51);

        assertThat(StoredNumbers.encoding(DocValuesType.NUMERIC, List.of(raw), List.of()))
                .isEmpty();
        // Another value than the stored one.
        assertThat(StoredNumbers.encoding(
                        DocValuesType.NUMERIC, List.of(raw), List.of(stored(StoredType.DOUBLE, "20.52"))))
                .isEmpty();
        // A different count.
        assertThat(StoredNumbers.encoding(
                        DocValuesType.SORTED_NUMERIC, List.of(raw, raw), List.of(stored(StoredType.DOUBLE, "20.51"))))
                .isEmpty();
        // Mixed types.
        assertThat(StoredNumbers.encoding(
                        DocValuesType.SORTED_NUMERIC,
                        List.of(1L, 2L),
                        List.of(stored(StoredType.LONG, "1"), stored(StoredType.STRING, "2"))))
                .isEqualTo(Optional.empty());
    }
}
