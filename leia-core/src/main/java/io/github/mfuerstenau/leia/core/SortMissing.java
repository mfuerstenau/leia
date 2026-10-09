package io.github.mfuerstenau.leia.core;

/**
 * Where documents without a value go in a field sort (ADR 0025). Lucene sets the missing value
 * before it reverses the order; {@link #FIRST} and {@link #LAST} mean the order shown, reversed or
 * not.
 */
public enum SortMissing {
    /** Lucene's default: as 0 in a numeric sort, first in an ascending {@code STRING} sort. */
    DEFAULT,
    /** First in the order shown. */
    FIRST,
    /** Last in the order shown. */
    LAST
}
