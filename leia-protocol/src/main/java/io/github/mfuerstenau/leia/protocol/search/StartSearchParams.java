package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;
import java.util.Optional;

/**
 * Params of {@code startSearch}.
 *
 * @param query the query
 * @param similarity what scores the hits
 * @param sort the order of the hits, one key after the other; empty for the best score first
 * @param offset how many of the first hits to skip; {@code offset + limit} is at most {@code
 *     MAX_WINDOW}
 * @param limit the most hits the page has, from 1 to {@code MAX_LIMIT}
 * @param exactTotalHits whether to count every hit; otherwise counting stops after 1000 hits
 * @param fields the stored fields to send per hit; absent for all of them
 * @param highlight how to highlight the query's matches in the hits' stored text fields, those of
 *     {@code fields}; absent for no highlighting (ADR 0042)
 */
public record StartSearchParams(
        SearchQuery query,
        Similarity similarity,
        List<SortKey> sort,
        int offset,
        int limit,
        boolean exactTotalHits,
        Optional<List<String>> fields,
        Optional<HighlightOptions> highlight) {

    /** The largest {@code limit} the server accepts. */
    public static final int MAX_LIMIT = 1000;

    /** The largest {@code offset + limit} the server accepts. */
    public static final int MAX_WINDOW = 10_000;
}
