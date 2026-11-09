package io.github.mfuerstenau.leia.runtime;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Queries over the doc values of one field, as leia-core's {@code DocValuesQueries} builds them on
 * Lucene 10 (ADR 0033, ADR 0036, ADR 0066). They read every value of the field, so they are slow,
 * but they find what was never indexed as terms or points. A field with several values per document
 * matches if any of them does. Each query is for {@link Search#search}, {@link Search#matches},
 * {@link Search#explain} and {@link Scoring}; it has no terms to highlight.
 */
public interface DocValuesSearch {

    /**
     * Documents by the numbers of a {@code NUMERIC} or {@code SORTED_NUMERIC} field.
     *
     * @param number how the field encodes its numbers
     * @param match the numbers as decimal text, as {@code number} parses it
     * @throws IllegalArgumentException if the field has no number doc values, or a value is not a
     *     number of {@code number} or is NaN
     */
    Search.ParsedQuery numbers(String field, NumberEncoding number, Match<String> match);

    /**
     * Documents by the bytes of a {@code SORTED}, {@code SORTED_SET} or {@code BINARY} field. Bytes
     * compare unsigned, byte by byte.
     *
     * @throws IllegalArgumentException if the field has no doc values of bytes
     */
    Search.ParsedQuery bytes(String field, Match<byte[]> match);

    /**
     * Documents by the bytes of a {@code SORTED}, {@code SORTED_SET} or {@code BINARY} field that
     * start with {@code prefix} (ADR 0036). Empty bytes match any value.
     *
     * @throws IllegalArgumentException if the field has no doc values of bytes
     */
    Search.ParsedQuery prefix(String field, byte[] prefix);

    /** How a number field's doc values encode their numbers, which the index doesn't record. */
    enum NumberEncoding {
        /** The long itself. */
        LONG,
        /** The raw bits of a double. */
        DOUBLE,
        /** A double's sortable long, as Lucene's {@code NumericUtils.doubleToSortableLong}. */
        SORTABLE_DOUBLE,
        /** The raw bits of a float. */
        FLOAT,
        /** A float's sortable int, as Lucene's {@code NumericUtils.floatToSortableInt}. */
        SORTABLE_FLOAT
    }

    /**
     * Which doc values a query matches: one of several values, or a range.
     *
     * @param <T> the type of a value: a number's decimal text, or bytes
     */
    // Each variant uses T (ADR 0088)
    @SuppressWarnings("java:S2326")
    sealed interface Match<T> {

        /**
         * Values equal to one of {@code values}.
         *
         * @throws IllegalArgumentException if {@code values} is empty
         */
        record Values<T>(List<T> values) implements Match<T> {

            public Values {
                if (values.isEmpty()) {
                    throw new IllegalArgumentException("no values to match");
                }
                values = List.copyOf(values);
            }
        }

        /** Values from {@code lower} to {@code upper}; an absent bound is open. */
        record Range<T>(@Nullable T lower, @Nullable T upper, boolean includeLower, boolean includeUpper)
                implements Match<T> {}
    }
}
