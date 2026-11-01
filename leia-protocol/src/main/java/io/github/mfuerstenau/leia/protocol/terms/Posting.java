package io.github.mfuerstenau.leia.protocol.terms;

import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import java.util.List;
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
 * @param positions the first occurrences of the term in the document, up to the params' {@code
 *     maxPositions}; absent without it. Empty where the field has no positions; shorter than {@code
 *     freq} if cut (ADR 0119)
 */
public record Posting(
        int docId,
        boolean deleted,
        OptionalInt freq,
        Optional<Boolean> inFilter,
        Optional<List<TermPosition>> positions) {

    /** A posting without positions. */
    public Posting(int docId, boolean deleted, OptionalInt freq, Optional<Boolean> inFilter) {
        this(docId, deleted, freq, inFilter, Optional.empty());
    }
}
