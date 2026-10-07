package io.github.mfuerstenau.leia.protocol.search;

import java.util.Optional;

/**
 * A query, as one of several alternatives: exactly one is present (ADR 0010, ADR 0011).
 *
 * @param classic an expression for Lucene's classic query parser
 * @param standard an expression for Lucene's standard query parser, which knows point fields
 * @param term a query for exactly one term
 */
public record SearchQuery(Optional<ClassicQuery> classic, Optional<StandardQuery> standard, Optional<TermQuery> term) {}
