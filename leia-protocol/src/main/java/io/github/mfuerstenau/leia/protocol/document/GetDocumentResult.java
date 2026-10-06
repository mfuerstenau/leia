package io.github.mfuerstenau.leia.protocol.document;

import java.util.List;

/**
 * Result of {@code getDocument}: what a document holds, field by field (ADR 0006).
 *
 * @param docId the document id
 * @param deleted whether the document is deleted; its data is still there until a merge
 * @param fields every field of the index, ordered by field number as in {@code getIndexStats}, with
 *     what this document has in it
 */
public record GetDocumentResult(int docId, boolean deleted, List<DocumentField> fields) {

    /**
     * The longest stored value, in bytes, that {@code getDocument} sends whole (ADR 0008). A longer
     * {@code STRING} or {@code BINARY} value is cut, and {@code getStoredValue} fetches it whole.
     */
    public static final int MAX_VALUE_LENGTH = 64 * 1024;
}
