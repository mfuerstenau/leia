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
public record GetDocumentResult(int docId, boolean deleted, List<DocumentField> fields) {}
