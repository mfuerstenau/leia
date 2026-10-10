package io.github.mfuerstenau.leia.protocol.filter;

import java.util.Optional;

/**
 * Result of {@code getFilter}: the progress of finding the matches, and the filter once it is
 * built.
 *
 * @param docsSearched the documents in the segments searched so far
 * @param result the filter; absent while the matches are being found
 */
public record GetFilterResult(long docsSearched, Optional<FilterResult> result) {}
