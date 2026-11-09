package io.github.mfuerstenau.leia.core;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A page of the postings of a term: the documents that contain it, in document id order, deleted
 * documents included; through a filter, only those in it, or all of them marked (ADR 0032).
 *
 * @param docFreq documents that contain the term, deleted ones included
 * @param totalTermFreq occurrences of the term in all documents; equal to {@code docFreq} if
 *     frequencies are not indexed
 * @param postings the postings of the page
 * @param nextDoc the document id after the page, from which the next page starts; null after the
 *     last posting, or the last one in the filter with {@code onlyMatches}
 */
public record PostingsPage(
        int docFreq,
        long totalTermFreq,
        List<Posting> postings,
        @Nullable Integer nextDoc) {

    /**
     * One document that contains the term.
     *
     * @param docId the document id
     * @param deleted whether the document is deleted
     * @param freq how often the term occurs in the document; null if frequencies are not indexed
     * @param inFilter whether the document is in the filter the postings were read through; null
     *     without one
     * @param positions the first occurrences of the term in the document, as many as asked for;
     *     null if none were; an empty list if positions are not indexed
     */
    public record Posting(
            int docId,
            boolean deleted,
            @Nullable Integer freq,
            @Nullable Boolean inFilter,
            @Nullable List<TermPosition> positions) {

        /** A posting read without a filter or positions. */
        public Posting(int docId, boolean deleted, @Nullable Integer freq) {
            this(docId, deleted, freq, null);
        }

        /** A posting read without positions. */
        public Posting(int docId, boolean deleted, @Nullable Integer freq, @Nullable Boolean inFilter) {
            this(docId, deleted, freq, inFilter, null);
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
