package io.github.mfuerstenau.leia.core;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A page of the postings of a term: the documents that contain it, in document id order, deleted
 * documents included; through a filter, only those in it, or all of them marked (ADR 0032).
 *
 * @param docFreq documents that contain the term, deleted ones included
 * @param totalTermFreq occurrences of the term in all documents; equal to {@code docFreq} if
 *     frequencies are not indexed
 * @param postings the postings of the page
 * @param nextDoc the document id after the page, from which the next page starts; empty after the
 *     last posting, or the last one in the filter with {@code onlyMatches}
 */
public record PostingsPage(int docFreq, long totalTermFreq, List<Posting> postings, OptionalInt nextDoc) {

    /**
     * One document that contains the term.
     *
     * @param docId the document id
     * @param deleted whether the document is deleted
     * @param freq how often the term occurs in the document; empty if frequencies are not indexed
     * @param inFilter whether the document is in the filter the postings were read through; empty
     *     without one
     */
    public record Posting(int docId, boolean deleted, OptionalInt freq, Optional<Boolean> inFilter) {

        /** A posting read without a filter. */
        public Posting(int docId, boolean deleted, OptionalInt freq) {
            this(docId, deleted, freq, Optional.empty());
        }
    }

    /**
     * The filter that postings are read through (ADR 0032).
     *
     * @param filter the documents of the filter
     * @param onlyMatches whether to read only the postings of documents in the filter; otherwise
     *     all of them, each marked
     */
    public record Filter(DocFilter filter, boolean onlyMatches) {}
}
