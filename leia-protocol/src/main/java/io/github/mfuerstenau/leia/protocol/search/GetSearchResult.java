package io.github.mfuerstenau.leia.protocol.search;

import java.util.Optional;

/**
 * Result of {@code getSearch}: the progress of the search, and its outcome once it is done.
 *
 * @param docsSearched the documents in the segments searched so far
 * @param results the outcome; absent while the search is running
 */
public record GetSearchResult(long docsSearched, Optional<SearchResults> results) {}
