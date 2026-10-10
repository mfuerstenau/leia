package io.github.mfuerstenau.leia.protocol.terms;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * One document that contains a term.
 *
 * @param docId the document id
 * @param deleted whether the document is deleted
 * @param freq how often the term occurs in the document; absent if frequencies are not indexed
 * @param inFilter whether the document is in the filter the postings were read through (ADR 0032);
 *     absent without one
 */
public record Posting(int docId, boolean deleted, OptionalInt freq, Optional<Boolean> inFilter) {}
