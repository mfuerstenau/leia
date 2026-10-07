package io.github.mfuerstenau.leia.protocol.search;

/**
 * Params of {@code startExplain}.
 *
 * @param query the query
 * @param similarity what scores the document; the search's, for the explanation to match its score
 * @param docId the document to explain, from 0 to maxDoc - 1
 */
public record StartExplainParams(SearchQuery query, Similarity similarity, int docId) {}
