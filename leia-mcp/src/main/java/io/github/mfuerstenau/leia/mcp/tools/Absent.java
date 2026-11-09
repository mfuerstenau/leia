package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.protocol.document.StoredType;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Results, {@code null} where left out, for values that are only worth a token when they differ from the default: a
 * result leaves them out, and the component's description says what its absence means (ADR 0103).
 */
final class Absent {

    private Absent() {}

    /** True if {@code value} is, else absent. */
    static @Nullable Boolean flag(boolean value) {
        return value ? Boolean.TRUE : null;
    }

    /** {@code list} unless it is empty. */
    static <T> @Nullable List<T> nonEmpty(List<T> list) {
        return list.isEmpty() ? null : list;
    }

    /** {@code value} unless it is 0. */
    static @Nullable Integer nonZero(int value) {
        return value == 0 ? null : value;
    }

    /** {@code type} unless it is {@code STRING}. */
    static @Nullable StoredType type(StoredType type) {
        return type == StoredType.STRING ? null : type;
    }
}
