package io.github.mfuerstenau.leia.core;

import java.time.Duration;
import java.util.List;
import org.apache.lucene.search.TotalHits;

/**
 * One page of the hits of a search (ADR 0010).
 *
 * @param query the query as Lucene writes it ({@code Query.toString()})
 * @param rewrittenQuery the query after rewriting, as Lucene writes it
 * @param totalHits how many documents match: exactly, or at least this many if counting stopped
 * @param hits the hits of the page, best first
 * @param took how long the search took
 */
public record SearchResults(String query, String rewrittenQuery, TotalHits totalHits, List<Hit> hits, Duration took) {

    /**
     * A matching document.
     *
     * @param docId the document id in the whole index
     * @param score the document's score for the query
     * @param fields the document's stored values in the fields asked for, in the order of each
     *     field's first value
     */
    public record Hit(int docId, float score, List<HitField> fields) {}

    /**
     * The stored values of a hit in one field.
     *
     * @param name the field name
     * @param values the values, in the order they were added
     */
    public record HitField(String name, List<IndexDocument.StoredValue> values) {}
}
