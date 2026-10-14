package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;

/**
 * The best passages of a hit in one field, with the query's matches in them (ADR 0042).
 *
 * @param field the field name
 * @param offsetSource where the offsets of the matches came from
 * @param passages at most {@link HighlightOptions#maxPassages} passages with at least one match,
 *     in the order of the field's values and their text
 */
public record HitHighlight(String field, OffsetSource offsetSource, List<HighlightPassage> passages) {}
