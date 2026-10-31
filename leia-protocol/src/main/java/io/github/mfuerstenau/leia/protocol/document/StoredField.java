package io.github.mfuerstenau.leia.protocol.document;

/**
 * A field that documents store values in (ADR 0113).
 *
 * @param name the field name
 * @param liveDocs the live documents with at least one stored value in the field
 * @param allDocs the documents with at least one stored value in the field, deleted ones included
 */
public record StoredField(String name, int liveDocs, int allDocs) {}
