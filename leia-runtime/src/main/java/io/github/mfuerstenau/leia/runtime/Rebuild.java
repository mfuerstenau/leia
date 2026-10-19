package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.LongConsumer;

/**
 * Rebuilds the terms and points a document was indexed with, field by field, as leia-core's {@code
 * Reconstruct} does on Lucene 10 (ADR 0029, ADR 0041, ADR 0059). A field whose term vector in the
 * document holds all that its postings hold is read from the term vector; any other field from the
 * postings, by advancing every term of the field in the document's segment to the document. The
 * points of a field are found by going through every point of the field in the segment. Deleted
 * documents are rebuilt too. The caller has checked the document and the fields.
 */
public interface Rebuild {

    /** How many terms or points the rebuild goes through between two reports of its progress. */
    int PROGRESS_INTERVAL = 1024;

    /**
     * How many terms and points {@link #rebuild} goes through: the terms of the fields it rebuilds
     * from the postings and the points of the fields, in the document's segment; empty if the segment
     * doesn't know how many terms a field has.
     */
    OptionalLong count(int docId, List<String> fields) throws IOException;

    /**
     * Rebuilds document {@code docId} in each of {@code fields}, in that order.
     *
     * @param progress gets the terms and points gone through so far; it throws to stop the rebuild
     */
    List<RebuiltField> rebuild(int docId, List<String> fields, LongConsumer progress) throws IOException;

    /** Where the terms of a field come from. */
    enum Source {
        /** The document's term vector, read whole. */
        TERM_VECTOR,
        /** The postings of every term of the field in the document's segment. */
        POSTINGS
    }

    /**
     * The terms and points a document was indexed with in a field.
     *
     * @param indexOptions what the postings of the field hold in the document's segment; {@code NONE}
     *     if the segment doesn't have the field
     * @param terms the terms in term order, with their occurrences in the document; {@code freq} is 1
     *     for postings without freqs, and the positions are empty for postings without positions
     * @param points the document's points in the field, packed, in unsigned byte order with duplicates
     *     kept; empty if the field has no points
     */
    record RebuiltField(
            String field,
            RuntimeIndex.IndexOptions indexOptions,
            Source source,
            List<Documents.VectorTerm> terms,
            List<byte[]> points) {}
}
