package io.github.mfuerstenau.leia.protocol.document;

import java.util.List;

/**
 * One term of a term vector.
 *
 * @param term the term as text; if {@code binary}, its bytes in lowercase hex instead
 * @param binary whether the term's bytes are not valid UTF-8, so {@code term} holds them as hex
 * @param freq how often the term occurs in the field of the document
 * @param positions one entry per occurrence, in order; empty if the term vector has neither
 *     positions nor offsets
 */
public record TermVectorTerm(String term, boolean binary, int freq, List<TermPosition> positions) {}
