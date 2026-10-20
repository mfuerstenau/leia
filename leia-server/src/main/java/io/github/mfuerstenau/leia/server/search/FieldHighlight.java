package io.github.mfuerstenau.leia.server.search;

import java.util.List;

/**
 * The best passages of a hit in one field (ADR 0042).
 *
 * @param field the field name
 * @param offsetSource where the offsets of the matches came from
 * @param passages the passages with at least one match, in the order of the values and their text
 */
public record FieldHighlight(String field, OffsetSource offsetSource, List<Passage> passages) {

    /**
     * A passage of a stored value with matches in it. Positions are UTF-8 byte offsets into the whole
     * value.
     *
     * @param valueIndex the value's position among the document's stored values in the field
     * @param start where {@code text} starts in the value
     * @param text the passage, cut around its first match if it is long
     * @param spans the matches in {@code text}, in order and not overlapping
     * @param score how well the passage matches
     */
    public record Passage(int valueIndex, int start, String text, List<StoredScan.Span> spans, float score) {}
}
