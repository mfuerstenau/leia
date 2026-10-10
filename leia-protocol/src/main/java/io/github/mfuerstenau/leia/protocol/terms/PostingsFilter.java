package io.github.mfuerstenau.leia.protocol.terms;

/**
 * The filter that a page of postings is read through (ADR 0032).
 *
 * @param filterId the filter, as {@code getFilter} returned it
 * @param onlyMatches whether the page has only the postings of documents in the filter; otherwise
 *     it has all of them, and says of each whether its document is in the filter
 */
public record PostingsFilter(long filterId, boolean onlyMatches) {}
