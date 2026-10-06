package io.github.mfuerstenau.leia.protocol.document;

import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import java.util.List;

/**
 * A document's doc values in one field.
 *
 * @param type the doc values type, never {@code NONE}
 * @param numbers the raw values of a {@code NUMERIC} or {@code SORTED_NUMERIC} field, in ascending
 *     order for the latter; empty for the other types. What they encode (a long, the bits of a
 *     double, a sortable double, …) is not recorded in the index.
 * @param values the values of a {@code BINARY}, {@code SORTED} or {@code SORTED_SET} field, in
 *     ascending order for the latter; empty for the other types
 */
public record DocValues(DocValuesType type, List<Long> numbers, List<BytesValue> values) {}
