package io.github.mfuerstenau.leia.client.values;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The encodings of numeric doc values (ADR 0033): decoding a raw number, encoding a stored one, and
 * telling which encoding a field has from the stored values of the same document.
 */
class DocValuesNumbersTest {

    @Test
    void decodesNumbers() {
        assertThat(DocValuesNumbers.format(DocValuesNumber.LONG, -3)).isEqualTo("-3");
        assertThat(DocValuesNumbers.format(DocValuesNumber.DOUBLE, Double.doubleToLongBits(2.5)))
                .isEqualTo("2.5");
        assertThat(DocValuesNumbers.format(DocValuesNumber.SORTABLE_DOUBLE, sortable(-2.5)))
                .isEqualTo("-2.5");
        assertThat(DocValuesNumbers.format(DocValuesNumber.FLOAT, Float.floatToIntBits(1.5f)))
                .isEqualTo("1.5");
        int floatBits = Float.floatToIntBits(-1.5f);
        assertThat(DocValuesNumbers.format(
                        DocValuesNumber.SORTABLE_FLOAT, floatBits ^ ((floatBits >> 31) & Integer.MAX_VALUE)))
                .isEqualTo("-1.5");
        // The raw bits of -1 as a double: a NaN.
        assertThat(DocValuesNumbers.format(DocValuesNumber.DOUBLE, -1)).isEqualTo("NaN");
    }

    @Test
    void encodesWhatItDecodes() {
        for (DocValuesNumber encoding : DocValuesNumber.values()) {
            String text = encoding == DocValuesNumber.LONG ? "-42" : "-12.5";
            long encoded = DocValuesNumbers.encode(encoding, text);
            assertThat(DocValuesNumbers.format(encoding, encoded))
                    .as(encoding.name())
                    .isEqualTo(text);
        }
        assertThat(DocValuesNumbers.encode(DocValuesNumber.SORTABLE_DOUBLE, "-2.5"))
                .isEqualTo(sortable(-2.5));
        assertThat(DocValuesNumbers.encode(DocValuesNumber.DOUBLE, "2.5")).isEqualTo(Double.doubleToRawLongBits(2.5));
    }

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

        assertThat(DocValuesNumbers.inferFromStored(DocValuesType.NUMERIC, List.of(raw), values))
                .contains(DocValuesNumber.DOUBLE);
        // Positive numbers are the same in both encodings; the field's type picks the name.
        assertThat(DocValuesNumbers.inferFromStored(DocValuesType.SORTED_NUMERIC, List.of(raw), values))
                .contains(DocValuesNumber.SORTABLE_DOUBLE);
    }

    @Test
    void decodesANegativeDoubleOnlyInTheEncodingThatWroteIt() {
        List<StoredValue> values = List.of(stored(StoredType.DOUBLE, "-1.5"));

        assertThat(DocValuesNumbers.inferFromStored(DocValuesType.NUMERIC, List.of(sortable(-1.5)), values))
                .contains(DocValuesNumber.SORTABLE_DOUBLE);
        assertThat(DocValuesNumbers.inferFromStored(
                        DocValuesType.SORTED_NUMERIC, List.of(Double.doubleToRawLongBits(-1.5)), values))
                .contains(DocValuesNumber.DOUBLE);
    }

    @Test
    void decodesFloatsAndIntegers() {
        assertThat(DocValuesNumbers.inferFromStored(
                        DocValuesType.NUMERIC,
                        List.of((long) Float.floatToRawIntBits(-2.5f)),
                        List.of(stored(StoredType.FLOAT, "-2.5"))))
                .contains(DocValuesNumber.FLOAT);
        assertThat(DocValuesNumbers.inferFromStored(
                        DocValuesType.NUMERIC, List.of(-7L), List.of(stored(StoredType.INT, "-7"))))
                .contains(DocValuesNumber.LONG);
    }

    @Test
    void matchesSeveralValuesWhateverTheirOrder() {
        assertThat(DocValuesNumbers.inferFromStored(
                        DocValuesType.SORTED_NUMERIC,
                        List.of(1L, 5L),
                        List.of(stored(StoredType.LONG, "5"), stored(StoredType.INT, "1"))))
                .contains(DocValuesNumber.LONG);
    }

    @Test
    void matchesNaNBitForBit() {
        assertThat(DocValuesNumbers.inferFromStored(
                        DocValuesType.NUMERIC,
                        List.of(Double.doubleToRawLongBits(Double.NaN)),
                        List.of(stored(StoredType.DOUBLE, "NaN"))))
                .contains(DocValuesNumber.DOUBLE);
    }

    @Test
    void guessesNothingWithoutAConfirmingStoredValue() {
        long raw = Double.doubleToRawLongBits(20.51);

        assertThat(DocValuesNumbers.inferFromStored(DocValuesType.NUMERIC, List.of(raw), List.of()))
                .isEmpty();
        // Another value than the stored one.
        assertThat(DocValuesNumbers.inferFromStored(
                        DocValuesType.NUMERIC, List.of(raw), List.of(stored(StoredType.DOUBLE, "20.52"))))
                .isEmpty();
        // A different count.
        assertThat(DocValuesNumbers.inferFromStored(
                        DocValuesType.SORTED_NUMERIC, List.of(raw, raw), List.of(stored(StoredType.DOUBLE, "20.51"))))
                .isEmpty();
        // Mixed types.
        assertThat(DocValuesNumbers.inferFromStored(
                        DocValuesType.SORTED_NUMERIC,
                        List.of(1L, 2L),
                        List.of(stored(StoredType.LONG, "1"), stored(StoredType.STRING, "2"))))
                .isEmpty();
    }
}
