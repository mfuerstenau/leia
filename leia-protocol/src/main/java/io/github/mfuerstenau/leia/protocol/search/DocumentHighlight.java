package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;

/**
 * The matches of a query in the stored values of a document (ADR 0042, ADR 0043).
 *
 * @param fields per field highlighted, in the order of the document's stored fields: the named
 *     field or each field with a text value, with matches or not; none for a query without terms
 */
public record DocumentHighlight(List<FieldMatches> fields) {}
