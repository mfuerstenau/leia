package io.github.mfuerstenau.leia.mcp.tools;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** The defaults of the arguments a client may leave out, which the records have as {@code null}. */
final class Given {

    private Given() {}

    /** Whether {@code flag} is given and true: false by default. */
    static boolean isTrue(@Nullable Boolean flag) {
        return Boolean.TRUE.equals(flag);
    }

    /** {@code list}, or an empty one without it. */
    static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list != null ? list : List.of();
    }
}
