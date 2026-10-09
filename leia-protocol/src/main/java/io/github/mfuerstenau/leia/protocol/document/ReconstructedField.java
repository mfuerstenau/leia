package io.github.mfuerstenau.leia.protocol.document;

import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import java.util.List;

/**
 * The terms a document was indexed with in a field (ADR 0029).
 *
 * @param field the field
 * @param indexOptions what the postings of the field hold in the document's segment; {@code NONE}
 *     if the segment doesn't have the field
 * @param source where the terms come from
 * @param terms the terms in term order, with their occurrences in the document; {@code freq} is 1
 *     for postings without freqs, and {@code positions} is empty for postings without positions
 */
public record ReconstructedField(
        String field, IndexOptions indexOptions, ReconstructSource source, List<TermVectorTerm> terms) {}
