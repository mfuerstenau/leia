package io.github.mfuerstenau.leia.protocol.document;

import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.points.PointValue;
import java.util.List;

/**
 * The terms and points a document was indexed with in a field (ADR 0029, ADR 0041). A field with
 * only points has the {@code indexOptions} {@code NONE}, the {@code source} {@code POSTINGS} and no
 * terms.
 *
 * @param field the field
 * @param indexOptions what the postings of the field hold in the document's segment; {@code NONE}
 *     if the segment doesn't have the field
 * @param source where the terms come from
 * @param terms the terms in term order, with their occurrences in the document; {@code freq} is 1
 *     for postings without freqs, and {@code positions} is empty for postings without positions
 * @param points the document's points in the field, in unsigned byte order with duplicates kept;
 *     empty if the field has no points
 */
public record ReconstructedField(
        String field,
        IndexOptions indexOptions,
        ReconstructSource source,
        List<TermVectorTerm> terms,
        List<PointValue> points) {}
