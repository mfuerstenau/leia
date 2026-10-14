package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;

/**
 * The matches of a query in one stored value (ADR 0042). Positions are UTF-8 byte offsets into the
 * whole value, as in ADR 0027.
 *
 * @param valueIndex the value's position among the document's stored values in the field, as
 *     {@code getStoredValue} takes it
 * @param matchCount how many matches the value has, overlapping ones merged
 * @param spans the first {@link StartStoredScanParams#MAX_SPANS} matches, in order
 */
public record ValueHighlight(int valueIndex, int matchCount, List<MatchSpan> spans) {}
