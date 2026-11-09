package io.github.mfuerstenau.leia.mcp.tools;

import java.util.OptionalInt;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/**
 * Between the tools' nullable numbers and the protocol's {@code OptionalInt} and {@code OptionalLong}:
 * {@code Optional} is only a return type outside the protocol's records (AGENTS.md, "Code
 * conventions").
 */
final class Optionals {

    private Optionals() {}

    /** {@code value}, or empty for {@code null}. */
    static OptionalInt optionalInt(@Nullable Integer value) {
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    /** {@code value}, or empty for {@code null}. */
    static OptionalLong optionalLong(@Nullable Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    /** {@code value}'s int, or {@code null} if it is empty. */
    static @Nullable Integer orNull(OptionalInt value) {
        return value.isPresent() ? value.getAsInt() : null;
    }

    /** {@code value}'s long, or {@code null} if it is empty. */
    static @Nullable Long orNull(OptionalLong value) {
        return value.isPresent() ? value.getAsLong() : null;
    }
}
