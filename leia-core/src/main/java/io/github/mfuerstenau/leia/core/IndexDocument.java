package io.github.mfuerstenau.leia.core;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.util.BytesRef;

/**
 * What one document holds, field by field. A deleted document still has its data until a merge
 * removes it.
 *
 * @param docId the document id in the whole index
 * @param deleted whether the document is deleted
 * @param fields every field of the index, ordered by field number, with what this document has in it
 */
public record IndexDocument(int docId, boolean deleted, List<Field> fields) {

    /**
     * What a document has in one field. Whether it has postings in the field is not known without
     * scanning the field's terms.
     *
     * @param name the field name
     * @param norm the document's norm; empty if the field has no norms or the document has none
     * @param stored the stored values, in the order they were added
     * @param docValues the document's doc values; empty if it has none in this field
     * @param hasTermVector whether the document has a term vector for the field
     */
    public record Field(
            String name,
            OptionalLong norm,
            List<StoredValue> stored,
            Optional<DocValues> docValues,
            boolean hasTermVector) {}

    /** A stored value, with the type it was stored as. */
    public sealed interface StoredValue {

        record Text(String value) implements StoredValue {}

        record Binary(BytesRef value) implements StoredValue {}

        record IntValue(int value) implements StoredValue {}

        record LongValue(long value) implements StoredValue {}

        record FloatValue(float value) implements StoredValue {}

        record DoubleValue(double value) implements StoredValue {}
    }

    /**
     * A document's doc values in one field.
     *
     * @param type the doc values type, never {@link DocValuesType#NONE}
     * @param numbers the values of a {@code NUMERIC} or {@code SORTED_NUMERIC} field, in ascending
     *     order for the latter; empty for the other types
     * @param values the values of a {@code BINARY}, {@code SORTED} or {@code SORTED_SET} field, in
     *     ascending order for the latter; empty for the other types
     */
    public record DocValues(DocValuesType type, List<Long> numbers, List<BytesRef> values) {}
}
