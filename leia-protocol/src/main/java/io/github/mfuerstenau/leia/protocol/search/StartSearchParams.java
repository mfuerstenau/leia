package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;
import java.util.Optional;

/**
 * Params of {@code startSearch}.
 *
 * @param query the query
 * @param offset how many of the best hits to skip; {@code offset + limit} is at most {@code
 *     MAX_WINDOW}
 * @param limit the most hits the page has, from 1 to {@code MAX_LIMIT}
 * @param exactTotalHits whether to count every hit; otherwise counting stops after 1000 hits
 * @param fields the stored fields to send per hit; absent for all of them
 */
public record StartSearchParams(
        SearchQuery query, int offset, int limit, boolean exactTotalHits, Optional<List<String>> fields) {

    /** The largest {@code limit} the server accepts. */
    public static final int MAX_LIMIT = 1000;

    /** The largest {@code offset + limit} the server accepts. */
    public static final int MAX_WINDOW = 10_000;
}
