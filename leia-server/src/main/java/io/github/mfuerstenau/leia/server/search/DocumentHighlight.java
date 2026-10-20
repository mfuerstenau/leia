package io.github.mfuerstenau.leia.server.search;

import java.util.List;

/**
 * The matches of a query in the stored text values of a document (ADR 0042, ADR 0043).
 *
 * @param fields per field highlighted, in the order of the document's stored fields: one named
 *     field, or each field with a text value
 */
public record DocumentHighlight(List<FieldMatches> fields) {

    /**
     * The matches in the stored text values of one field.
     *
     * @param field the field
     * @param offsetSource where the offsets of the matches came from
     * @param values the values with at least one match, in order
     * @param truncated whether the field's values are longer than the highlighter reads, so that
     *     later matches are missing
     */
    public record FieldMatches(
            String field, OffsetSource offsetSource, List<ValueHighlight> values, boolean truncated) {}

    /**
     * The matches in one value. Positions are UTF-8 byte offsets into the whole value.
     *
     * @param valueIndex the value's position among the document's stored values in the field
     * @param matchCount how many matches the value has, overlapping ones merged
     * @param spans the first matches, in order
     */
    public record ValueHighlight(int valueIndex, int matchCount, List<StoredScan.Span> spans) {}
}
