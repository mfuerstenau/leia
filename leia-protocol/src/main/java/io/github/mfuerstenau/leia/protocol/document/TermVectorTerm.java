package io.github.mfuerstenau.leia.protocol.document;

import java.util.List;

/**
 * One term of a document in a field, from its term vector or rebuilt from the postings (ADR 0029).
 *
 * @param term the term as text; if {@code binary}, its bytes in lowercase hex instead
 * @param binary whether the term's bytes are not valid UTF-8, so {@code term} holds them as hex
 * @param freq how often the term occurs in the field of the document; 1 from postings without freqs
 * @param positions one entry per occurrence, in order; empty if the term vector has neither
 *     positions nor offsets, or the postings have no positions
 */
public record TermVectorTerm(String term, boolean binary, int freq, List<TermPosition> positions) {}
