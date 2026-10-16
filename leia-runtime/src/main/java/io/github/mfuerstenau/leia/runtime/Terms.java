package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.LongConsumer;

/**
 * The terms and postings of a {@link RuntimeIndex}. Terms are bytes: the UTF-8 of text, or anything
 * else, such as the trie-encoded terms of a numeric field of Lucene 4 and 5 (ADR 0046).
 */
public interface Terms {

    /** Up to {@code limit} terms of {@code field}, from {@code from} on (or from its first term). */
    TermsPage terms(String field, Optional<byte[]> from, int limit) throws IOException;

    /** Up to {@code limit} postings of {@code term} in {@code field}, from document {@code fromDoc} on. */
    PostingsPage postings(String field, byte[] term, int fromDoc, int limit) throws IOException;

    /**
     * Where {@code term} occurs in document {@code docId}, with offsets and payloads as far as they
     * were indexed; an empty list if positions are not indexed.
     *
     * @return empty if the document doesn't contain the term in {@code field}
     * @throws IllegalArgumentException if there is no such document
     */
    Optional<List<Documents.Position>> positions(int docId, String field, byte[] term) throws IOException;

    /**
     * Counts the terms of {@code field} and finds the {@code topN} with the highest document
     * frequency, deleted documents included, ties keeping the earlier term (ADR 0045).
     *
     * @param progress receives the terms scanned so far, from time to time and at the end; it may
     *     throw to stop the scan, which then throws what it threw
     */
    FieldTerms topTerms(String field, int topN, LongConsumer progress) throws IOException;

    /**
     * The terms of a field.
     *
     * @param topTerms highest document frequency first, ties in term order
     */
    record FieldTerms(long uniqueTerms, List<TermEntry> topTerms) {}

    /**
     * A term of a field.
     *
     * @param totalTermFreq counted from the postings of live documents if the index doesn't record it
     */
    record TermEntry(byte[] term, int docFreq, long totalTermFreq) {}

    /** A page of terms, and the term after it. */
    record TermsPage(List<TermEntry> terms, Optional<byte[]> next) {}

    /**
     * A document that contains a term, and how often.
     *
     * @param deleted whether the document is deleted
     */
    record Posting(int docId, int freq, boolean deleted) {}

    /**
     * A page of postings.
     *
     * @param postings deleted documents too, marked, if the runtime's Lucene can read their postings;
     *     otherwise live documents only
     */
    record PostingsPage(int docFreq, long totalTermFreq, List<Posting> postings, OptionalInt nextDoc) {}
}
