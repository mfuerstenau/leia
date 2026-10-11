package io.github.mfuerstenau.leia.tui.documents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code decodes_numbers} of {@code documents.rs}, and how floats print as Rust's {@code {:?}}. */
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

    @Test
    void printsFloatsAsRustDoes() {
        assertThat(NumberFormat.debug(2.0)).isEqualTo("2.0");
        assertThat(NumberFormat.debug(-0.0)).isEqualTo("-0.0");
        assertThat(NumberFormat.debug(0.1)).isEqualTo("0.1");
        assertThat(NumberFormat.debug(1e-4)).isEqualTo("0.0001");
        assertThat(NumberFormat.debug(1.5e-5)).isEqualTo("1.5e-5");
        assertThat(NumberFormat.debug(1e15)).isEqualTo("1000000000000000.0");
        assertThat(NumberFormat.debug(1e16)).isEqualTo("1e16");
        assertThat(NumberFormat.debug(-1.25e20)).isEqualTo("-1.25e20");
        assertThat(NumberFormat.debug(Double.NaN)).isEqualTo("NaN");
        assertThat(NumberFormat.debug(Double.POSITIVE_INFINITY)).isEqualTo("inf");
        assertThat(NumberFormat.debug(Double.NEGATIVE_INFINITY)).isEqualTo("-inf");
        assertThat(NumberFormat.debug(Double.MIN_VALUE)).isEqualTo("5e-324");
        assertThat(NumberFormat.debug(0.1f)).isEqualTo("0.1");
        assertThat(NumberFormat.debug(16777216f)).isEqualTo("16777216.0");
        assertThat(NumberFormat.debug(3.4028235e38f)).isEqualTo("3.4028235e38");
        // The raw bits of -1 as a double: a NaN.
        assertThat(NumberFormat.DOUBLE.format(-1)).isEqualTo("NaN");
    }
}
