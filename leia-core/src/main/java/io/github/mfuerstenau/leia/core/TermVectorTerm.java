package io.github.mfuerstenau.leia.core;

import java.util.List;
import org.apache.lucene.util.BytesRef;

/**
 * One term of a document in a field, from its term vector or rebuilt from the postings (ADR 0029).
 *
 * @param term the term's bytes, which are UTF-8 for text but can be anything
 * @param freq how often the term occurs in the field of the document; 1 from postings without freqs
 * @param positions one entry per occurrence, in order; empty if the term vector has neither
 *     positions nor offsets, or the postings have no positions
 */
public record TermVectorTerm(BytesRef term, int freq, List<TermPosition> positions) {}
