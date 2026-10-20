package io.github.mfuerstenau.leia.server.filter;

/**
 * The filter that postings are read through (ADR 0032).
 *
 * @param filter the documents of the filter
 * @param onlyMatches whether to read only the postings of documents in the filter; otherwise all of
 *     them, each marked
 */
public record PostingsFilter(DocFilter filter, boolean onlyMatches) {}
