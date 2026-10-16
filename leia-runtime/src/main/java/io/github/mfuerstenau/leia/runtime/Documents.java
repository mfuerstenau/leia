package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** The documents of a {@link RuntimeIndex}. */
public interface Documents {

    /**
     * Reads document {@code docId}, a deleted one too.
     *
     * @throws IllegalArgumentException if there is no such document
     */
    IndexDocument document(int docId) throws IOException;

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
     */
    record DocumentField(
            String name,
            OptionalLong norm,
            List<RuntimeIndex.StoredValue> stored,
            Optional<DocValues> docValues,
            boolean hasTermVector) {}

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

    /** A document, with every field of the index in field-number order. */
    record IndexDocument(int docId, boolean deleted, List<DocumentField> fields) {}

    /**
     * One occurrence of a term, in a term vector or in the postings.
     *
     * @param payload its payload; empty if it has none
     */
    record Position(OptionalInt position, OptionalInt startOffset, OptionalInt endOffset, Optional<byte[]> payload) {}

    /** A term of a term vector. */
    record VectorTerm(byte[] term, int freq, List<Position> positions) {}
}
