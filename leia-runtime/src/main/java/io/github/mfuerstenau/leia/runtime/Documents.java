package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

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
     * else of a document is read (ADR 0055).
     *
     * @param fields the fields whose values to read; empty for all of them
     * @throws IllegalArgumentException unless {@code 0 <= fromDoc <= toDoc <= maxDoc}
     */
    List<StoredDocument> storedValues(int fromDoc, int toDoc, Optional<Set<String>> fields, boolean includeDeleted)
            throws IOException;

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
