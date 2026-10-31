package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import java.util.function.LongConsumer;

/** The documents of a {@link RuntimeIndex}. */
public interface Documents {

    /**
     * Reads document {@code docId}, a deleted one too.
     *
     * @throws IllegalArgumentException if there is no such document
     */
    IndexDocument document(int docId) throws IOException;

    /**
     * The stored values of the documents from {@code fromDoc} up to {@code toDoc} (exclusive), in
     * document id order: every live one, and the deleted ones too if {@code includeDeleted}. Nothing
     * else of a document is read (ADR 0055). For a few documents, such as a highlight's; a scan reads
     * a segment at a time through {@link #storedValues(int, Optional, boolean, IntUnaryOperator,
     * Consumer, LongConsumer)}.
     *
     * @param fields the fields whose values to read; empty for all of them
     * @throws IllegalArgumentException unless {@code 0 <= fromDoc <= toDoc <= maxDoc}
     */
    List<StoredDocument> storedValues(int fromDoc, int toDoc, Optional<Set<String>> fields, boolean includeDeleted)
            throws IOException;

    /**
     * The stored values of the documents of a segment, in document order, as {@link
     * #storedValues(int, int, Optional, boolean)} reads them, but each block of documents decompressed
     * once (ADR 0114): through the codec's merge instance, as {@link #storedFields} reads them.
     *
     * @param segment as {@link #storedFields} takes it
     * @param fields the fields whose values to read; empty for all of them
     * @param includeDeleted whether to read the deleted documents too
     * @param next the first segment-local document at or after the one it gets that is to be read, or
     *     the segment's maxDoc or more for none; the documents it passes over aren't read. {@link
     *     IntUnaryOperator#identity()} reads them all
     * @param sink gets each document read, with its segment-local id
     * @param progress gets the documents of the segment gone through so far, now and then and at the
     *     end; it throws to stop the scan, which passes the exception on
     * @throws IllegalArgumentException if there is no such segment
     */
    void storedValues(
            int segment,
            Optional<Set<String>> fields,
            boolean includeDeleted,
            IntUnaryOperator next,
            Consumer<StoredDocument> sink,
            LongConsumer progress)
            throws IOException;

    /**
     * Which fields the documents of a segment store values in, without reading the values (ADR 0113):
     * {@code sink} gets the segment-local id of every document, deleted ones too, with the field of
     * each of its stored values, in document order and the order the values were stored.
     *
     * @param segment the segment's position among those of all directories, in the order of {@link
     *     RuntimeIndex.Stats#indexes()} and of their {@link RuntimeIndex.IndexStats#segments()}
     * @param progress gets the documents of the segment read so far, now and then and at the end; it
     *     throws to stop the scan, which passes the exception on
     * @throws IllegalArgumentException if there is no such segment
     */
    void storedFields(int segment, StoredFieldSink sink, LongConsumer progress) throws IOException;

    /**
     * The deleted documents of a segment, segment-local and ascending (ADR 0113).
     *
     * @param segment as {@link #storedFields} takes it
     * @throws IllegalArgumentException if there is no such segment
     */
    int[] deletedDocs(int segment) throws IOException;

    /** Receives the stored fields of a segment's documents (ADR 0113). */
    @FunctionalInterface
    interface StoredFieldSink {

        /** Document {@code doc} has a stored value in {@code field}. */
        void accept(int doc, String field);
    }

    /**
     * The term vector of a document in {@code field}, in term order; empty if it has none.
     *
     * @throws IllegalArgumentException if there is no such document
     */
    List<VectorTerm> termVector(int docId, String field) throws IOException;

    /**
     * What a document has in one field of the index.
     *
     * @param docValues its doc values; empty if it has none in the field
     * @param vector its KNN vector; empty if it has none in the field, as always before Lucene 9
     */
    record DocumentField(
            String name,
            OptionalLong norm,
            List<RuntimeIndex.StoredValue> stored,
            Optional<DocValues> docValues,
            boolean hasTermVector,
            Optional<RuntimeIndex.Vector> vector) {

        /** What a document has in a field, without a KNN vector, as before Lucene 9. */
        public DocumentField(
                String name,
                OptionalLong norm,
                List<RuntimeIndex.StoredValue> stored,
                Optional<DocValues> docValues,
                boolean hasTermVector) {
            this(name, norm, stored, docValues, hasTermVector, Optional.empty());
        }
    }

    /**
     * A document's doc values in one field.
     *
     * @param type never {@code NONE}
     * @param numbers the values of a {@code NUMERIC} or {@code SORTED_NUMERIC} field, ascending for
     *     the latter; empty for the other types
     * @param values the values of a {@code BINARY}, {@code SORTED} or {@code SORTED_SET} field,
     *     ascending for the latter; empty for the other types
     */
    record DocValues(RuntimeIndex.DocValuesType type, List<Long> numbers, List<byte[]> values) {}

    /**
     * A document's stored values (ADR 0055).
     *
     * @param values the values by field, the fields in the order of their first value, each field's
     *     values in stored order, as {@link DocumentField#stored()} has them
     */
    record StoredDocument(int docId, boolean deleted, Map<String, List<RuntimeIndex.StoredValue>> values) {}

    /** A document, with every field of the index in field-number order. */
    record IndexDocument(int docId, boolean deleted, List<DocumentField> fields) {}

    /**
     * One occurrence of a term, in a term vector or in the postings.
     *
     * @param payload its payload; empty if it has none
     */
    record Position(OptionalInt position, OptionalInt startOffset, OptionalInt endOffset, Optional<byte[]> payload) {}

    /** A term of a term vector. */
    // Never compared, hashed or used as a key (ADR 0088)
    @SuppressWarnings("java:S6218")
    record VectorTerm(byte[] term, int freq, List<Position> positions) {}
}
