package io.github.mfuerstenau.leia.protocol.search;

/**
 * Params of {@code startHighlight}: the matches of a query in every stored value of one field of a
 * document (ADR 0042).
 *
 * @param query the query; a doc values or KNN query has no terms, so nothing is marked
 * @param docId the document, from 0 to maxDoc - 1
 * @param field the stored field
 * @param options how to highlight; {@code maxPassages} is not used
 */
public record StartHighlightParams(SearchQuery query, int docId, String field, HighlightOptions options) {}
