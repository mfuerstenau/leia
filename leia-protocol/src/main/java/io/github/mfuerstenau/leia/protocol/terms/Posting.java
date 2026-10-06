package io.github.mfuerstenau.leia.protocol.terms;

import java.util.OptionalInt;

/**
 * One document that contains a term.
 *
 * @param docId the document id
 * @param deleted whether the document is deleted
 * @param freq how often the term occurs in the document; absent if frequencies are not indexed
 */
public record Posting(int docId, boolean deleted, OptionalInt freq) {}
