package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.protocol.document.StoredType;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Optional results for values that are only worth a token when they differ from the default: a
 * result leaves them out, and the component's description says what its absence means (ADR 0103).
 */
final class Absent {

    private Absent() {}

    /** True if {@code value} is, else absent. */
    static Optional<Boolean> flag(boolean value) {
        return value ? Optional.of(true) : Optional.empty();
    }

    /** {@code list} unless it is empty. */
    static <T> Optional<List<T>> nonEmpty(List<T> list) {
        return list.isEmpty() ? Optional.empty() : Optional.of(list);
    }

    /** {@code value} unless it is 0. */
    static OptionalInt nonZero(int value) {
        return value == 0 ? OptionalInt.empty() : OptionalInt.of(value);
    }

    /** {@code type} unless it is {@code STRING}. */
    static Optional<StoredType> type(StoredType type) {
        return type == StoredType.STRING ? Optional.empty() : Optional.of(type);
    }
}
