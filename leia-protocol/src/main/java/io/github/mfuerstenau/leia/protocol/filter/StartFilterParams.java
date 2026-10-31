package io.github.mfuerstenau.leia.protocol.filter;

import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import java.util.Optional;

/**
 * Params of {@code startFilter} (ADR 0031): the documents that the documents screen is narrowed to,
 * those that match a query, those with a stored value in which a pattern is found (ADR 0032), or
 * those with a stored value in a field (ADR 0114). Exactly one of {@code query}, {@code pattern} and
 * {@code storedField} is present.
 *
 * @param query the query, as for {@code startSearch}
 * @param pattern the pattern, as for {@code startStoredScan}
 * @param storedField the field, as {@code startStoredFieldsScan} finds its documents
 * @param includeDeleted whether deleted documents match too
 */
public record StartFilterParams(
        Optional<SearchQuery> query,
        Optional<FilterPattern> pattern,
        Optional<String> storedField,
        boolean includeDeleted) {}
