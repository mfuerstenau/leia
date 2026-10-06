package io.github.mfuerstenau.leia.protocol.document;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What a document has in one field. How the field is indexed is in {@code getIndexStats}; whether
 * the document has postings in the field is not reported.
 *
 * @param name the field name
 * @param norm the document's norm; absent if the field has no norms or the document has none
 * @param stored the stored values, in the order they were added
 * @param docValues the document's doc values; absent if it has none in this field
 * @param hasTermVector whether the document has a term vector for the field ({@code getTermVector})
 */
public record DocumentField(
        String name,
        OptionalLong norm,
        List<StoredValue> stored,
        Optional<DocValues> docValues,
        boolean hasTermVector) {}
