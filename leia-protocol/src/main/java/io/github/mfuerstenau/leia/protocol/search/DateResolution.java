package io.github.mfuerstenau.leia.protocol.search;

/**
 * How precisely the dates of a range were indexed, as Lucene's {@code DateTools.Resolution}
 * (ADR 0026); serialized as its name.
 */
public enum DateResolution {
    YEAR,
    MONTH,
    DAY,
    HOUR,
    MINUTE,
    SECOND,
    MILLISECOND
}
