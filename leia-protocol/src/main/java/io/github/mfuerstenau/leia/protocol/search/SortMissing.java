package io.github.mfuerstenau.leia.protocol.search;

/**
 * Where documents without a value go in a field sort (ADR 0025); serialized as its name.
 */
public enum SortMissing {
    /** Lucene's default: as 0 in a numeric sort, first in an ascending {@code STRING} sort. */
    DEFAULT,
    /** First in the order shown, also when it is reversed. */
    FIRST,
    /** Last in the order shown, also when it is reversed. */
    LAST
}
