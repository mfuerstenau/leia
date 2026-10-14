package io.github.mfuerstenau.leia.protocol.search;

import java.util.Optional;

/**
 * Params of {@code startHighlight}: the matches of a query in every stored value of one field of a
 * document, or of all its stored fields (ADR 0042, ADR 0043).
 *
 * @param query the query; a doc values or KNN query has no terms, so nothing is marked
 * @param docId the document, from 0 to maxDoc - 1
 * @param field the stored field; absent for each stored field of the document with a text value
 * @param options how to highlight; {@code maxPassages} is not used
 */
public record StartHighlightParams(SearchQuery query, int docId, Optional<String> field, HighlightOptions options) {}
