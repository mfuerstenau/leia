package io.github.mfuerstenau.leia.core;

import java.time.Duration;
import java.util.Optional;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.FixedBitSet;

/**
 * The documents that match a query, as a set of the document ids of the whole index (ADR 0031): one
 * bit per document, so it takes maxDoc / 8 bytes. It can be read from any thread once it is built.
 */
public final class DocFilter {

    private final FixedBitSet matches;
    private final int matchCount;
    private final String query;
    private final String rewrittenQuery;
    private final Duration took;

    /**
     * A matching document.
     *
     * @param docId the document id in the whole index
     * @param index how many matching documents come before it
     */
    public record Match(int docId, int index) {}

    DocFilter(FixedBitSet matches, String query, String rewrittenQuery, Duration took) {
        this.matches = matches;
        this.matchCount = matches.cardinality();
        this.query = query;
        this.rewrittenQuery = rewrittenQuery;
        this.took = took;
    }

    /** How many documents match. */
    public int matchCount() {
        return matchCount;
    }

    /** The query as Lucene writes it ({@code Query.toString()}). */
    public String query() {
        return query;
    }

    /** The query after rewriting, as Lucene writes it. */
    public String rewrittenQuery() {
        return rewrittenQuery;
    }

    /** How long finding the matches took. */
    public Duration took() {
        return took;
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
            int from = Math.max(fromDoc, 0);
            docId = from < maxDoc ? matches.nextSetBit(from) : DocIdSetIterator.NO_MORE_DOCS;
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
