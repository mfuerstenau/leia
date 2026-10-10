package io.github.mfuerstenau.leia.protocol.filter;

/**
 * A document that matches a filter.
 *
 * @param docId the document id
 * @param index how many matching documents come before it, so that it is match {@code index + 1}
 *     of {@code matchCount}
 */
public record FilterMatch(int docId, int index) {}
