package io.github.mfuerstenau.leia.protocol.search;

import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import java.util.List;

/**
 * The stored values of a hit in one field.
 *
 * @param name the field name
 * @param values the values, in the order they were added
 */
public record HitField(String name, List<StoredValue> values) {}
