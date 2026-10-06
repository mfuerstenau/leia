package io.github.mfuerstenau.leia.core;

import java.util.List;
import java.util.Optional;
import org.apache.lucene.util.BytesRef;

/**
 * A page of the terms of a field, in term order, across all segments. Like the postings statistics,
 * the frequencies include deleted documents.
 *
 * @param terms the terms of the page
 * @param next the term after the page, from which the next page starts; empty after the last term
 */
public record TermPage(List<Entry> terms, Optional<BytesRef> next) {

    /**
     * One term of a field.
     *
     * @param term the term's bytes, which are UTF-8 for text but can be anything
     * @param docFreq documents that contain the term
     * @param totalTermFreq occurrences of the term in all documents; equal to {@code docFreq} if
     *     frequencies are not indexed
     */
    public record Entry(BytesRef term, int docFreq, long totalTermFreq) {}
}
