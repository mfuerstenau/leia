package io.github.mfuerstenau.leia.protocol.search;

/**
 * A sort by a field's doc values (ADR 0012, ADR 0025).
 *
 * @param field a field with doc values other than {@code BINARY}
 * @param type how the field's doc values are read
 * @param reverse whether the largest value comes first
 * @param selector which value of a multi-valued field a document sorts by; {@code MIN} for a
 *     single-valued one
 * @param missing where documents without a value go
 */
public record FieldSort(String field, SortType type, boolean reverse, SortSelector selector, SortMissing missing) {}
