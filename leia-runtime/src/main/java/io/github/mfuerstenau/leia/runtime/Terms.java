package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** The terms and postings of a {@link RuntimeIndex}. Terms are text. */
public interface Terms {

    /** Up to {@code limit} terms of {@code field}, from {@code from} on (or from its first term). */
    TermsPage terms(String field, Optional<String> from, int limit) throws IOException;

    /** Up to {@code limit} postings of {@code term} in {@code field}, from document {@code fromDoc} on. */
    PostingsPage postings(String field, String term, int fromDoc, int limit) throws IOException;

    /**
     * A term of a field.
     *
     * @param totalTermFreq counted from the postings of live documents if the index doesn't record it
     */
    record TermEntry(String term, int docFreq, long totalTermFreq) {}

    /** A page of terms, and the term after it. */
    record TermsPage(List<TermEntry> terms, Optional<String> next) {}

    /** A document that contains a term, and how often. */
    record Posting(int docId, int freq) {}

    /**
     * A page of postings.
     *
     * @param postings live documents only
     */
    record PostingsPage(int docFreq, long totalTermFreq, List<Posting> postings, OptionalInt nextDoc) {}
}
