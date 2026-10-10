package io.github.mfuerstenau.leia.protocol.filter;

/**
 * A filter that the server built and keeps (ADR 0031), for {@code stepFilter} and {@code
 * releaseFilter}.
 *
 * @param filterId the filter; the server keeps one, so a newer filter replaces it
 * @param matchCount how many documents match
 * @param query the query as Lucene writes it
 * @param rewrittenQuery the query after rewriting, as Lucene writes it
 * @param tookMillis how long finding the matches took
 */
public record FilterResult(long filterId, int matchCount, String query, String rewrittenQuery, long tookMillis) {}
