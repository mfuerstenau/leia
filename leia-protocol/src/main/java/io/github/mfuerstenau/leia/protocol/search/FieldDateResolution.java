package io.github.mfuerstenau.leia.protocol.search;

/**
 * The resolution that range bounds on one field are turned into terms with, when they are dates
 * (ADR 0035).
 *
 * @param field the field
 * @param resolution how precisely the field's dates were indexed
 */
public record FieldDateResolution(String field, DateResolution resolution) {}
