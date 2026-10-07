package io.github.mfuerstenau.leia.protocol.search;

/**
 * Params of {@code startExplain}.
 *
 * @param query the query
 * @param docId the document to explain, from 0 to maxDoc - 1
 */
public record StartExplainParams(SearchQuery query, int docId) {}
