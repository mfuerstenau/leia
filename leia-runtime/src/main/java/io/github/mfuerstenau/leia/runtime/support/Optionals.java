package io.github.mfuerstenau.leia.runtime.support;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/**
 * Between nullable numbers and {@code OptionalInt}, {@code OptionalLong} and {@code OptionalDouble},
 * where a record still has or needs them: {@code Optional} is only a return type outside the
 * protocol's records (AGENTS.md, "Code conventions"; ADR 0127).
 */
public final class Optionals {

    private Optionals() {}

    /** {@code value}, or empty for {@code null}. */
    public static OptionalInt optionalInt(@Nullable Integer value) {
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    /** {@code value}, or empty for {@code null}. */
    public static OptionalLong optionalLong(@Nullable Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    /** {@code value}, or empty for {@code null}. */
    public static OptionalDouble optionalDouble(@Nullable Double value) {
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    /** {@code value}'s int, or {@code null} if it is empty. */
    public static @Nullable Integer orNull(OptionalInt value) {
        return value.isPresent() ? value.getAsInt() : null;
    }

    /** {@code value}'s long, or {@code null} if it is empty. */
    public static @Nullable Long orNull(OptionalLong value) {
        return value.isPresent() ? value.getAsLong() : null;
    }

    /** {@code value}'s double, or {@code null} if it is empty. */
    public static @Nullable Double orNull(OptionalDouble value) {
        return value.isPresent() ? value.getAsDouble() : null;
    }
}
