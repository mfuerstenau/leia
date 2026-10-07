package io.github.mfuerstenau.leia.protocol.search;

/**
 * One level of the order of the hits (ADR 0012).
 *
 * @param field a field with doc values other than {@code BINARY}
 * @param type how the field's doc values are read
 * @param reverse whether the largest value comes first
 */
public record SortKey(String field, SortType type, boolean reverse) {}
