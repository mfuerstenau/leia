package io.github.mfuerstenau.leia.protocol.search;

import io.github.mfuerstenau.leia.protocol.document.BytesValue;

/**
 * A query for exactly one term, which is neither analyzed nor split.
 *
 * @param field the field
 * @param term the term
 */
public record TermQuery(String field, BytesValue term) {}
