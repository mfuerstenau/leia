package io.github.mfuerstenau.leia.core;

import java.util.List;
import java.util.Optional;

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
     * Values equal to one of {@code values}.
     *
     * @throws IllegalArgumentException if {@code values} is empty
     */
    record Values<T>(List<T> values) implements DocValuesMatch<T> {

        public Values {
            if (values.isEmpty()) {
                throw new IllegalArgumentException("no values to match");
            }
            values = List.copyOf(values);
        }
    }

    /** Values from {@code lower} to {@code upper}; an absent bound is open. */
    record Range<T>(Optional<T> lower, Optional<T> upper, boolean includeLower, boolean includeUpper)
            implements DocValuesMatch<T> {}
}
