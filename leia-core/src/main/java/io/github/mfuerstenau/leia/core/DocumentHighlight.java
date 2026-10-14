package io.github.mfuerstenau.leia.core;

import java.util.List;

/**
 * The matches of a query in the stored text values of one field of a document (ADR 0042).
 *
 * @param offsetSource where the offsets of the matches came from
 * @param values the values with at least one match, in order
 * @param truncated whether the values are longer than the highlighter reads, so that later matches
 *     are missing
 */
public record DocumentHighlight(OffsetSource offsetSource, List<ValueHighlight> values, boolean truncated) {

    /**
     * The matches in one value. Positions are UTF-8 byte offsets into the whole value.
     *
     * @param valueIndex the value's position among the document's stored values in the field
     * @param matchCount how many matches the value has, overlapping ones merged
     * @param spans the first matches, in order
     */
    public record ValueHighlight(int valueIndex, int matchCount, List<StoredScan.Span> spans) {}
}
