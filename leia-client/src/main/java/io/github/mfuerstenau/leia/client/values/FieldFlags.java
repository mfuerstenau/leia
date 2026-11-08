package io.github.mfuerstenau.leia.client.values;

import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import java.util.Optional;

/** What a document has in a field, as letters, as the documents screen and the export show them. */
public final class FieldFlags {

    /** The letters of {@link #of}, in order. */
    public static final String LETTERS = "INPSVDTK";

    private FieldFlags() {}

    /**
     * {@code I} indexed, {@code N} norm, {@code P} payloads, {@code S} stored, {@code V} term vector,
     * {@code D} doc values, {@code T} points, {@code K} KNN vector; {@code -} where absent. {@code N},
     * {@code S}, {@code V}, {@code D} and {@code K} are about this document, the others about the
     * field.
     */
    public static String of(Optional<IndexField> schema, DocumentField field) {
        boolean[] set = {
            schema.filter(f -> f.indexOptions() != IndexOptions.NONE).isPresent(),
            field.norm().isPresent(),
            schema.filter(IndexField::hasPayloads).isPresent(),
            !field.stored().isEmpty(),
            field.hasTermVector(),
            field.docValues().isPresent(),
            schema.filter(f -> f.pointDimensionCount() > 0).isPresent(),
            field.vector().isPresent()
        };
        StringBuilder flags = new StringBuilder();
        for (int i = 0; i < set.length; i++) {
            flags.append(set[i] ? LETTERS.charAt(i) : '-');
        }
        return flags.toString();
    }

    /**
     * Whether the document has nothing that {@code getDocument} reports in the field. It may still
     * have postings there.
     */
    public static boolean isEmpty(DocumentField field) {
        return field.norm().isEmpty()
                && field.stored().isEmpty()
                && field.docValues().isEmpty()
                && !field.hasTermVector()
                && field.vector().isEmpty();
    }
}
