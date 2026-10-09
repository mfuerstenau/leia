package io.github.mfuerstenau.leia.protocol.search;

/**
 * A stored value: of a document, in a field, at a position among the field's values.
 *
 * @param docId the document id
 * @param field the field name
 * @param valueIndex the value's position among the document's stored values in the field
 */
public record ValueRef(int docId, String field, int valueIndex) {}
