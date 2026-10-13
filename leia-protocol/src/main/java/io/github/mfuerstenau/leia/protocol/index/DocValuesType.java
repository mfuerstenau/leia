package io.github.mfuerstenau.leia.protocol.index;

/** The doc values type of a field, as Lucene's {@code DocValuesType}. */
public enum DocValuesType {
    NONE,
    NUMERIC,
    BINARY,
    SORTED,
    SORTED_NUMERIC,
    SORTED_SET
}
