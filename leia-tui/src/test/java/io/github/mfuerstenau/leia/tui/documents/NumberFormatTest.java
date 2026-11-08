package io.github.mfuerstenau.leia.tui.documents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code decodes_numbers} of {@code documents.rs}; how floats print is {@code RustDebugTest}'s. */
class NumberFormatTest {

    @Test
    void decodesNumbers() {
        assertThat(NumberFormat.LONG.format(-3)).isEqualTo("-3");
        assertThat(NumberFormat.DOUBLE.format(Double.doubleToLongBits(2.5))).isEqualTo("2.5");
        // NumericUtils.doubleToSortableLong(-2.5) and floatToSortableInt(1.5f).
        long bits = Double.doubleToLongBits(-2.5);
        assertThat(NumberFormat.SORTABLE_DOUBLE.format(bits ^ ((bits >> 63) & Long.MAX_VALUE)))
                .isEqualTo("-2.5");
        assertThat(NumberFormat.FLOAT.format(Float.floatToIntBits(1.5f))).isEqualTo("1.5");
        int floatBits = Float.floatToIntBits(-1.5f);
        assertThat(NumberFormat.SORTABLE_FLOAT.format(floatBits ^ ((floatBits >> 31) & Integer.MAX_VALUE)))
                .isEqualTo("-1.5");
    }
}
