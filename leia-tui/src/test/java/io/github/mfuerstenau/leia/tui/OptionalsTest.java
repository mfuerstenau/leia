package io.github.mfuerstenau.leia.tui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OptionalsTest {

    @Test
    void anIntegerIsPresentAndNullIsEmpty() {
        assertThat(Optionals.optionalInt(7)).hasValue(7);
        assertThat(Optionals.optionalInt(null)).isEmpty();
    }

    @Test
    void aLongIsPresentAndNullIsEmpty() {
        assertThat(Optionals.optionalLong(7L)).hasValue(7L);
        assertThat(Optionals.optionalLong(null)).isEmpty();
    }

    @Test
    void anOptionalIntIsBoxedAndEmptyIsNull() {
        assertThat(Optionals.orNull(java.util.OptionalInt.of(3))).isEqualTo(3);
        assertThat(Optionals.orNull(java.util.OptionalInt.empty())).isNull();
    }

    @Test
    void anOptionalLongIsBoxedAndEmptyIsNull() {
        assertThat(Optionals.orNull(java.util.OptionalLong.of(3))).isEqualTo(3L);
        assertThat(Optionals.orNull(java.util.OptionalLong.empty())).isNull();
    }
}
