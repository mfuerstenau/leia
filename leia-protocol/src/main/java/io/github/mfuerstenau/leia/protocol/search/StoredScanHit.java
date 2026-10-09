package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;

/**
 * A stored value in which the pattern of a scan was found (ADR 0027). Positions are UTF-8 byte
 * offsets into the whole value, as lengths are in ADR 0008.
 *
 * @param docId the document id
 * @param deleted whether the document is deleted
 * @param field the field name
 * @param valueIndex the value's position among the document's stored values in the field, as {@code
 *     getStoredValue} takes it
 * @param length the value's length in bytes
 * @param matchCount how many matches the value has
 * @param spans the first {@link StartStoredScanParams#MAX_SPANS} matches, in order
 * @param snippet the value around the first match
 */
public record StoredScanHit(
        int docId,
        boolean deleted,
        String field,
        int valueIndex,
        int length,
        int matchCount,
        List<MatchSpan> spans,
        Snippet snippet) {}
