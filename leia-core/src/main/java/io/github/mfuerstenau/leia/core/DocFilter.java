package io.github.mfuerstenau.leia.core;

import java.time.Duration;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Optional;
import java.util.OptionalLong;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.FixedBitSet;
import org.jspecify.annotations.Nullable;

/**
 * The documents that match a query (ADR 0031), or have a stored value in which a pattern is found
 * (ADR 0032), as a set of the document ids of the whole index: one bit per document, so it takes
 * maxDoc / 8 bytes. It can be read from any thread once it is built.
 */
public final class DocFilter {

    private final FixedBitSet matches;
    private final int matchCount;
    private final String query;
    private final String rewrittenQuery;
    private final Duration took;
    private final @Nullable Long timedOutValues;

    /**
     * A matching document.
     *
     * @param docId the document id in the whole index
     * @param index how many matching documents come before it
     */
    public record Match(int docId, int index) {}

    DocFilter(FixedBitSet matches, String query, String rewrittenQuery, Duration took) {
        this(matches, query, rewrittenQuery, took, null);
    }

    DocFilter(FixedBitSet matches, String query, String rewrittenQuery, Duration took, @Nullable Long timedOutValues) {
        this.matches = matches;
        this.matchCount = matches.cardinality();
        this.query = query;
        this.rewrittenQuery = rewrittenQuery;
        this.took = took;
        this.timedOutValues = timedOutValues;
    }

    /**
     * The filter of the documents set in {@code docs}, such as a runtime's matches of a query (ADR
     * 0056).
     *
     * @throws IllegalArgumentException if a document from {@code maxDoc} on is set
     */
    public static DocFilter of(BitSet docs, int maxDoc, String query, String rewrittenQuery, Duration took) {
        if (docs.length() > maxDoc) {
            throw new IllegalArgumentException(
                    "Document " + (docs.length() - 1) + " is not in the index: maxDoc is " + maxDoc);
        }
        long[] words = Arrays.copyOf(docs.toLongArray(), FixedBitSet.bits2words(maxDoc));
        return new DocFilter(new FixedBitSet(words, maxDoc), query, rewrittenQuery, took);
    }

    /** How many documents match. */
    public int matchCount() {
        return matchCount;
    }

    /**
     * The query as Lucene writes it ({@code Query.toString()}), or the pattern as {@code /pattern/},
     * with an {@code i} after it if case doesn't matter.
     */
    public String query() {
        return query;
    }

    /** The query after rewriting, as Lucene writes it; the pattern as in {@link #query}. */
    public String rewrittenQuery() {
        return rewrittenQuery;
    }

    /** How long finding the matches took. */
    public Duration took() {
        return took;
    }

    /**
     * For a pattern, the values that were skipped because matching them took too long (ADR 0027):
     * their documents may be missing. Empty for a query.
     */
    public OptionalLong timedOutValues() {
        return timedOutValues != null ? OptionalLong.of(timedOutValues) : OptionalLong.empty();
    }

    /** Whether {@code docId} matches; an id outside 0 to maxDoc - 1 doesn't. */
    public boolean contains(int docId) {
        return docId >= 0 && docId < matches.length() && matches.get(docId);
    }

    /**
     * The first matching document from {@code fromDoc} on, or {@link DocIdSetIterator#NO_MORE_DOCS}
     * if there is none; {@code fromDoc} may be anything.
     */
    public int next(int fromDoc) {
        int from = Math.max(fromDoc, 0);
        return from < matches.length() ? matches.nextSetBit(from) : DocIdSetIterator.NO_MORE_DOCS;
    }

    /**
     * The first matching document from {@code fromDoc} on, or the last one up to {@code fromDoc}
     * going back; {@code fromDoc} itself counts. Any {@code fromDoc} is taken: one below 0 or from
     * maxDoc on finds nothing in its direction, and everything in the other.
     */
    public Optional<Match> step(int fromDoc, boolean forward) {
        int maxDoc = matches.length();
        int docId;
        if (forward) {
            docId = next(fromDoc);
        } else {
            int from = Math.min(fromDoc, maxDoc - 1);
            docId = from >= 0 ? matches.prevSetBit(from) : -1;
        }
        if (docId < 0 || docId == DocIdSetIterator.NO_MORE_DOCS) {
            return Optional.empty();
        }
        return Optional.of(new Match(docId, countBelow(docId)));
    }

    /** How many matching documents have an id below {@code docId}. */
    private int countBelow(int docId) {
        long[] words = matches.getBits();
        int word = docId >> 6;
        int count = 0;
        for (int i = 0; i < word; i++) {
            count += Long.bitCount(words[i]);
        }
        // The bits of the last word below docId; a shift by 0 keeps none.
        long below = (1L << (docId & 63)) - 1;
        return count + Long.bitCount(words[word] & below);
    }
}
