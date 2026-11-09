package io.github.mfuerstenau.leia.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class OptionalsTest {

    @Test
    void aNumberIsPresentAndNullIsEmpty() {
        assertThat(Optionals.optionalInt(7)).hasValue(7);
        assertThat(Optionals.optionalInt(null)).isEmpty();
        assertThat(Optionals.optionalLong(7L)).hasValue(7L);
        assertThat(Optionals.optionalLong(null)).isEmpty();
        assertThat(Optionals.optionalDouble(0.5)).hasValue(0.5);
        assertThat(Optionals.optionalDouble(null)).isEmpty();
    }

    @Test
    void anOptionalNumberIsBoxedAndEmptyIsNull() {
        assertThat(Optionals.orNull(OptionalInt.of(3))).isEqualTo(3);
        assertThat(Optionals.orNull(OptionalInt.empty())).isNull();
        assertThat(Optionals.orNull(OptionalLong.of(3))).isEqualTo(3L);
        assertThat(Optionals.orNull(OptionalLong.empty())).isNull();
        assertThat(Optionals.orNull(OptionalDouble.of(0.5))).isEqualTo(0.5);
        assertThat(Optionals.orNull(OptionalDouble.empty())).isNull();
    }
}
