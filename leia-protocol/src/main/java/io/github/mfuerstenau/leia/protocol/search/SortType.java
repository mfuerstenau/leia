package io.github.mfuerstenau.leia.protocol.search;

/**
 * How a field's doc values are read to sort by them (ADR 0012); serialized as its name. Numeric doc
 * values take {@code INT}, {@code LONG}, {@code FLOAT} or {@code DOUBLE}, sorted and sorted set doc
 * values {@code STRING}.
 */
public enum SortType {
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    STRING
}
