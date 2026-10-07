package io.github.mfuerstenau.leia.protocol.search;

import java.util.Optional;

/**
 * A query, as one of several alternatives: exactly one is present (ADR 0010).
 *
 * @param classic an expression for Lucene's classic query parser
 * @param term a query for exactly one term
 */
public record SearchQuery(Optional<ClassicQuery> classic, Optional<TermQuery> term) {}
