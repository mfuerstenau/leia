package io.github.mfuerstenau.leia.protocol.document;

/**
 * Params of {@code getTermVector}.
 *
 * @param docId the document id, from 0 to maxDoc - 1
 * @param field the field
 */
public record GetTermVectorParams(int docId, String field) {}
