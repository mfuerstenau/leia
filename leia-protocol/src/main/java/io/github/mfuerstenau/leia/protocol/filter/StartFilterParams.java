package io.github.mfuerstenau.leia.protocol.filter;

import io.github.mfuerstenau.leia.protocol.search.SearchQuery;

/**
 * Params of {@code startFilter} (ADR 0031): a query whose matching documents the documents screen
 * is narrowed to.
 *
 * @param query the query, as for {@code startSearch}
 * @param includeDeleted whether deleted documents match too
 */
public record StartFilterParams(SearchQuery query, boolean includeDeleted) {}
