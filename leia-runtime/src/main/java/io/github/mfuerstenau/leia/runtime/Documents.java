package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.List;
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

    /** What a document has in one field of the index. */
    record DocumentField(
            String name, OptionalLong norm, List<RuntimeIndex.StoredValue> stored, boolean hasTermVector) {}

    /** A document, with every field of the index in field-number order. */
    record IndexDocument(int docId, boolean deleted, List<DocumentField> fields) {}

    /** One occurrence of a term in a term vector. */
    record Position(OptionalInt position, OptionalInt startOffset, OptionalInt endOffset) {}

    /** A term of a term vector. */
    record VectorTerm(String term, int freq, List<Position> positions) {}
}
