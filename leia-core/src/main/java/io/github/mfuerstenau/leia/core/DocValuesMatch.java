package io.github.mfuerstenau.leia.core;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Which doc values a {@link DocValuesQueries} query matches (ADR 0033): one of several values, or a
 * range.
 *
 * @param <T> the type of a value: a number's decimal text, or bytes
 */
// Each variant uses T (ADR 0088)
@SuppressWarnings("java:S2326")
public sealed interface DocValuesMatch<T> {

    /**
     * Values equal to one of {@code values}, which must not be empty; else the constructor throws
     * {@link IllegalArgumentException}.
     */
    record Values<T>(List<T> values) implements DocValuesMatch<T> {

        public Values {
            if (values.isEmpty()) {
                throw new IllegalArgumentException("no values to match");
            }
            values = List.copyOf(values);
        }
    }

    /** Values from {@code lower} to {@code upper}; a null bound is open. */
    record Range<T>(@Nullable T lower, @Nullable T upper, boolean includeLower, boolean includeUpper)
            implements DocValuesMatch<T> {}
}
