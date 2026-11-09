package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.List;
import java.util.function.LongConsumer;
import org.jspecify.annotations.Nullable;

/**
 * Search and explain with a similarity of the caller's choice, and search in an order of its choice
 * too, as leia-core searches on Lucene 10 (ADR 0012, ADR 0025, ADR 0064). leia checks the sort
 * against the fields' doc values first; a runtime still refuses a sort it can't run.
 */
public interface Scoring {

    /**
     * Searches with a query from {@link Search#parse}, {@link Search#term} or {@link Knn}, scored by
     * {@code similarity} and ordered by {@code sort}. A field sort reads every match's doc value and
     * never skips documents (ADR 0012), unless the hits aren't all counted. The hits of a field sort
     * carry their scores too, and each hit carries its value of each sort key.
     *
     * @param sort the order of the hits; empty for the best score first
     * @param exactTotalHits whether every hit is counted, as for {@link Search#search(Search.ParsedQuery,
     *     int, int, boolean, List, LongConsumer)} (ADR 0070)
     * @param fields the stored fields to return per hit; empty for all of them
     * @param progress receives the documents searched so far, from time to time; it may throw to stop
     *     the search
     * @throws IllegalArgumentException if a field of {@code sort} has no doc values that sort as its
     *     type, or with its selector
     */
    // Separate options of the call (ADR 0088)
    @SuppressWarnings("java:S107")
    Search.SearchResults search(
            Search.ParsedQuery query,
            Similarity similarity,
            List<SortKey> sort,
            int offset,
            int limit,
            boolean exactTotalHits,
            @Nullable List<String> fields,
            LongConsumer progress)
            throws IOException;

    /** Searches as {@link #search(Search.ParsedQuery, Similarity, List, int, int, boolean, List, LongConsumer)}, counting every hit. */
    default Search.SearchResults search(
            Search.ParsedQuery query,
            Similarity similarity,
            List<SortKey> sort,
            int offset,
            int limit,
            @Nullable List<String> fields,
            LongConsumer progress)
            throws IOException {
        return search(query, similarity, sort, offset, limit, true, fields, progress);
    }

    /**
     * How a query scores document {@code docId} with {@code similarity}, as {@link #search} scores it.
     * A deleted document doesn't match.
     *
     * @param progress receives the documents searched so far, from time to time; it may throw to stop
     *     the explanation
     */
    Search.Explanation explain(Search.ParsedQuery query, Similarity similarity, int docId, LongConsumer progress)
            throws IOException;

    /** What scores the hits. Each scores the norms the index has, whatever similarity wrote them. */
    sealed interface Similarity {

        /** Lucene's default, {@code BM25Similarity}; Lucene's defaults are {@code k1} 1.2, {@code b} 0.75. */
        record Bm25(float k1, float b) implements Similarity {}

        /** TF-IDF, {@code ClassicSimilarity}. */
        record Classic() implements Similarity {}

        /** A matching term scores its boost, {@code BooleanSimilarity}. */
        record Bool() implements Similarity {}
    }

    /** One level of the order of the hits. */
    sealed interface SortKey {

        /** By the score, highest first unless {@code reverse}. */
        record Score(boolean reverse) implements SortKey {}

        /** By the document id, lowest first unless {@code reverse}. */
        record Doc(boolean reverse) implements SortKey {}

        /**
         * By a field's doc values.
         *
         * @param type how the doc values are read
         * @param reverse whether the largest value comes first
         * @param selector which value of a multi-valued field a document sorts by
         * @param missing where documents without a value go, in the order shown
         */
        record Field(String field, SortType type, boolean reverse, SortSelector selector, SortMissing missing)
                implements SortKey {}
    }

    /** How a field's doc values are read to sort by them; numeric doc values are longs in the index. */
    enum SortType {
        INT,
        LONG,
        FLOAT,
        DOUBLE,
        STRING
    }

    /** Which value of a multi-valued field a document sorts by. */
    enum SortSelector {
        MIN,
        MAX,
        /** The middle value, or the smaller of the two middle values. */
        MIDDLE_MIN,
        /** The middle value, or the larger of the two middle values. */
        MIDDLE_MAX
    }

    /**
     * Where documents without a value go. Lucene sets the missing value before it reverses the order;
     * {@link #FIRST} and {@link #LAST} mean the order shown, reversed or not.
     */
    enum SortMissing {
        /** Lucene's default: as 0 in a numeric sort, first in an ascending {@code STRING} sort. */
        DEFAULT,
        FIRST,
        LAST
    }

    /**
     * The value a hit was sorted by in one sort key: a number (the score of a score key, the id of a
     * doc key, the value of a numeric field key) or a term. Both are empty for a document without a
     * value in a field key.
     */
    record SortValue(@Nullable Number number, byte @Nullable [] term) {}
}
