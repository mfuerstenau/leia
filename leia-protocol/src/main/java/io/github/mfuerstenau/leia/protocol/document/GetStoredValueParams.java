package io.github.mfuerstenau.leia.protocol.document;

/**
 * Params of {@code getStoredValue}.
 *
 * @param docId the document id, from 0 to maxDoc - 1
 * @param field the field
 * @param index which of the document's stored values in the field, from 0, in the order {@code
 *     getDocument} lists them
 */
public record GetStoredValueParams(int docId, String field, int index) {}
