package io.github.mfuerstenau.leia.protocol.document;

import java.util.List;

/**
 * Result of {@code getTermVector}.
 *
 * @param terms the terms in term order; empty if the document has no term vector for the field
 */
public record GetTermVectorResult(List<TermVectorTerm> terms) {}
