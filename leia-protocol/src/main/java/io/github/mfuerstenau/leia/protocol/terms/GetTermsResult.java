package io.github.mfuerstenau.leia.protocol.terms;

import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import java.util.List;
import java.util.Optional;

/**
 * Result of {@code getTerms}: a page of the terms of a field, in term order (ADR 0007).
 *
 * @param terms the terms of the page; empty if the field has no postings or no term from {@code
 *     from} on
 * @param next the term after the page, the {@code from} of the next page; absent after the last
 *     term
 */
public record GetTermsResult(List<TermEntry> terms, Optional<BytesValue> next) {}
