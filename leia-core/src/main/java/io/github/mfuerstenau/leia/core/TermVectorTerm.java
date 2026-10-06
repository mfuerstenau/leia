package io.github.mfuerstenau.leia.core;

import java.util.List;
import org.apache.lucene.util.BytesRef;

/**
 * One term of a document's term vector for a field.
 *
 * @param term the term's bytes, which are UTF-8 for text but can be anything
 * @param freq how often the term occurs in the field of the document
 * @param positions one entry per occurrence, in order; empty if the term vector has neither
 *     positions nor offsets
 */
public record TermVectorTerm(BytesRef term, int freq, List<TermPosition> positions) {}
