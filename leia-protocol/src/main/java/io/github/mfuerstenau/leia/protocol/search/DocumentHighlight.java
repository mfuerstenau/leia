package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;

/**
 * The matches of a query in the stored values of one field of a document (ADR 0042).
 *
 * @param offsetSource where the offsets of the matches came from
 * @param values the values with at least one match, in order
 * @param truncated whether the field's values are longer than {@link
 *     HighlightOptions#MAX_DOCUMENT_CHARS} characters together, so that later matches are missing
 */
public record DocumentHighlight(OffsetSource offsetSource, List<ValueHighlight> values, boolean truncated) {}
