package io.github.mfuerstenau.leia.protocol.search;

import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import java.util.List;
import java.util.Optional;

/**
 * Documents by the doc values of one field (ADR 0033): those with a value equal to one of {@code
 * values}, or with a value in {@code range}. Exactly one of the two is present. A field with several
 * values per document matches if any of them does.
 *
 * @param field a field with doc values
 * @param number how the values of a {@code NUMERIC} or {@code SORTED_NUMERIC} field are encoded;
 *     present for those fields only
 * @param values the values to match, at least one: a number's decimal text for a number field (as
 *     text, never binary), the bytes otherwise
 * @param range the values to match, from a lower to an upper bound
 */
public record DocValuesQuery(
        String field,
        Optional<DocValuesNumber> number,
        Optional<List<BytesValue>> values,
        Optional<DocValuesRange> range) {}
