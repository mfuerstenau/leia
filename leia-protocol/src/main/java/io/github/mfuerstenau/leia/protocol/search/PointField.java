package io.github.mfuerstenau.leia.protocol.search;

/**
 * A point field for the standard query parser, with the type of its values (ADR 0011).
 *
 * @param field a field with one-dimensional points as wide as {@code type}
 * @param type the type of the field's values
 */
public record PointField(String field, PointType type) {}
