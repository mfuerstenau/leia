package io.github.mfuerstenau.leia.client.values;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** How floats print, as Rust's {@code {:?}} prints them. */
class RustDebugTest {

    @Test
    void printsDoublesAsRustDoes() {
        assertThat(RustDebug.of(2.0)).isEqualTo("2.0");
        assertThat(RustDebug.of(-0.0)).isEqualTo("-0.0");
        assertThat(RustDebug.of(0.1)).isEqualTo("0.1");
        assertThat(RustDebug.of(1e-4)).isEqualTo("0.0001");
        assertThat(RustDebug.of(1.5e-5)).isEqualTo("1.5e-5");
        assertThat(RustDebug.of(1e15)).isEqualTo("1000000000000000.0");
        assertThat(RustDebug.of(1e16)).isEqualTo("1e16");
        assertThat(RustDebug.of(-1.25e20)).isEqualTo("-1.25e20");
        assertThat(RustDebug.of(Double.NaN)).isEqualTo("NaN");
        assertThat(RustDebug.of(Double.POSITIVE_INFINITY)).isEqualTo("inf");
        assertThat(RustDebug.of(Double.NEGATIVE_INFINITY)).isEqualTo("-inf");
        assertThat(RustDebug.of(Double.MIN_VALUE)).isEqualTo("5e-324");
    }

    @Test
    void printsFloatsFromTheirOwnShortestForm() {
        assertThat(RustDebug.of(0.1f)).isEqualTo("0.1");
        assertThat(RustDebug.of(16777216f)).isEqualTo("16777216.0");
        assertThat(RustDebug.of(3.4028235e38f)).isEqualTo("3.4028235e38");
        assertThat(RustDebug.of(-0.0f)).isEqualTo("-0.0");
        assertThat(RustDebug.of(1.5e-5f)).isEqualTo("1.5e-5");
        assertThat(RustDebug.of(Float.NaN)).isEqualTo("NaN");
        assertThat(RustDebug.of(Float.POSITIVE_INFINITY)).isEqualTo("inf");
        assertThat(RustDebug.of(Float.NEGATIVE_INFINITY)).isEqualTo("-inf");
    }
}
