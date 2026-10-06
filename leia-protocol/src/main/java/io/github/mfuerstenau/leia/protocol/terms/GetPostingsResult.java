package io.github.mfuerstenau.leia.protocol.terms;

import java.util.List;
import java.util.OptionalInt;

/**
 * Result of {@code getPostings}: a page of the documents that contain a term, in document id order,
 * deleted documents included (ADR 0007).
 *
 * @param docFreq documents that contain the term, deleted ones included
 * @param totalTermFreq occurrences of the term in all documents; equal to {@code docFreq} if
 *     frequencies are not indexed
 * @param postings the postings of the page
 * @param nextDoc the document id after the page, the {@code fromDoc} of the next page; absent after
 *     the last posting
 */
public record GetPostingsResult(int docFreq, long totalTermFreq, List<Posting> postings, OptionalInt nextDoc) {}
