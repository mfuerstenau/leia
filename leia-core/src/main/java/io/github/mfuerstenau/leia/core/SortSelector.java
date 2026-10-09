package io.github.mfuerstenau.leia.core;

import org.apache.lucene.search.SortedNumericSelector;
import org.apache.lucene.search.SortedSetSelector;

/**
 * Which value of a multi-valued field a document sorts by (ADR 0025). Single-valued doc values have
 * one value, so only {@link #MIN} applies to them; sorted numeric doc values take {@link #MIN} or
 * {@link #MAX}, sorted set doc values any of them.
 */
public enum SortSelector {
    /** The smallest value: Lucene's default. */
    MIN,
    /** The largest value. */
    MAX,
    /** The middle value, or the smaller of the two middle values. */
    MIDDLE_MIN,
    /** The middle value, or the larger of the two middle values. */
    MIDDLE_MAX;

    SortedNumericSelector.Type numeric() {
        return switch (this) {
            case MIN -> SortedNumericSelector.Type.MIN;
            case MAX -> SortedNumericSelector.Type.MAX;
            case MIDDLE_MIN, MIDDLE_MAX -> throw new IllegalStateException("No numeric selector " + this);
        };
    }

    SortedSetSelector.Type set() {
        return switch (this) {
            case MIN -> SortedSetSelector.Type.MIN;
            case MAX -> SortedSetSelector.Type.MAX;
            case MIDDLE_MIN -> SortedSetSelector.Type.MIDDLE_MIN;
            case MIDDLE_MAX -> SortedSetSelector.Type.MIDDLE_MAX;
        };
    }
}
