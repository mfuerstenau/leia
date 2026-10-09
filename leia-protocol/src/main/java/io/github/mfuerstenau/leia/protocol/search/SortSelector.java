package io.github.mfuerstenau.leia.protocol.search;

/**
 * Which value of a multi-valued field a document sorts by (ADR 0025); serialized as its name.
 * Single-valued doc values take {@code MIN} only, sorted numeric ones {@code MIN} or {@code MAX},
 * and sorted set ones any of them.
 */
public enum SortSelector {
    /** The smallest value: Lucene's default. */
    MIN,
    /** The largest value. */
    MAX,
    /** The middle value, or the smaller of the two middle values. */
    MIDDLE_MIN,
    /** The middle value, or the larger of the two middle values. */
    MIDDLE_MAX
}
