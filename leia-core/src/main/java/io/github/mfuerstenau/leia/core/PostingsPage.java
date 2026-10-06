package io.github.mfuerstenau.leia.core;

import java.util.List;
import java.util.OptionalInt;

/**
 * A page of the postings of a term: the documents that contain it, in document id order, deleted
 * documents included.
 *
 * @param docFreq documents that contain the term, deleted ones included
 * @param totalTermFreq occurrences of the term in all documents; equal to {@code docFreq} if
 *     frequencies are not indexed
 * @param postings the postings of the page
 * @param nextDoc the document id after the page, from which the next page starts; empty after the
 *     last posting
 */
public record PostingsPage(int docFreq, long totalTermFreq, List<Posting> postings, OptionalInt nextDoc) {

    /**
     * One document that contains the term.
     *
     * @param docId the document id
     * @param deleted whether the document is deleted
     * @param freq how often the term occurs in the document; empty if frequencies are not indexed
     */
    public record Posting(int docId, boolean deleted, OptionalInt freq) {}
}
