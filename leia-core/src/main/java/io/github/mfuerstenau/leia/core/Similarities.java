package io.github.mfuerstenau.leia.core;

import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.BooleanSimilarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.search.similarities.Similarity;

/**
 * The similarities that {@link ReadOnlyIndex#search} and {@link ReadOnlyIndex#explain} score with
 * (ADR 0012). Each scores the norms that the index has, whatever similarity wrote them: every
 * similarity here encodes a field's length in its norm the same way. "Discount overlaps" only
 * matters while indexing, so it is not offered.
 */
public final class Similarities {

    private Similarities() {}

    /**
     * Lucene's default, {@link BM25Similarity}.
     *
     * @param k1 how quickly a term's frequency saturates, finite and 0 or more (Lucene: 1.2)
     * @param b how much the field length normalizes, from 0 to 1 (Lucene: 0.75)
     * @throws IllegalArgumentException if {@code k1} or {@code b} is out of range
     */
    public static Similarity bm25(float k1, float b) {
        return new BM25Similarity(k1, b);
    }

    /** TF-IDF, {@link ClassicSimilarity}. */
    public static Similarity classic() {
        return new ClassicSimilarity();
    }

    /** A matching term scores its boost, {@link BooleanSimilarity}. */
    public static Similarity bool() {
        return new BooleanSimilarity();
    }
}
