package io.github.mfuerstenau.leia.protocol.filter;

import java.util.OptionalLong;

/**
 * A filter that the server built and keeps (ADR 0031), for {@code stepFilter} and {@code
 * releaseFilter}.
 *
 * @param filterId the filter; the server keeps one, so a newer filter replaces it
 * @param matchCount how many documents match
 * @param query the query as Lucene writes it, or the pattern as {@code /pattern/} with an {@code i}
 *     after it if case doesn't matter
 * @param rewrittenQuery the query after rewriting, as Lucene writes it; the pattern as in {@code
 *     query}
 * @param tookMillis how long finding the matches took
 * @param timedOutValues for a pattern, the values that were skipped because matching them took too
 *     long (ADR 0027), whose documents may be missing; absent for a query
 */
public record FilterResult(
        long filterId,
        int matchCount,
        String query,
        String rewrittenQuery,
        long tookMillis,
        OptionalLong timedOutValues) {}
