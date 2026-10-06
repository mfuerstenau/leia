package io.github.mfuerstenau.leia.protocol.document;

/**
 * Params of {@code getDocument}.
 *
 * @param docId the document id, from 0 to maxDoc - 1; a deleted document can be read too
 */
public record GetDocumentParams(int docId) {}
