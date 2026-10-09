package io.github.mfuerstenau.leia.core;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.util.BytesRef;

/**
 * One page of the hits of a search (ADR 0010).
 *
 * @param query the query as Lucene writes it ({@code Query.toString()})
 * @param rewrittenQuery the query after rewriting, as Lucene writes it
 * @param totalHits how many documents match: exactly, or at least this many if counting stopped
 * @param hits the hits of the page, best first
 * @param took how long the search took
 * @param moreLikeThisTerms for a More-Like-This query, the terms it chose, best first (ADR 0028)
 */
public record SearchResults(
        String query,
        String rewrittenQuery,
        TotalHits totalHits,
        List<Hit> hits,
        Duration took,
        Optional<List<LikeTerm>> moreLikeThisTerms) {

    /**
     * A matching document.
     *
     * @param docId the document id in the whole index
     * @param score the document's score for the query, also when the hits are sorted by fields
     * @param sortValues the values the hit was sorted by, one per sort field; empty without a sort
     * @param fields the document's stored values in the fields asked for, in the order of each
     *     field's first value
     */
    public record Hit(int docId, float score, List<SortValue> sortValues, List<HitField> fields) {}

    /**
     * The value a hit was sorted by in one sort field (ADR 0012, ADR 0025): a number (of a numeric
     * field, a score or a document id), or a term of a {@code STRING} sort. Neither is present for a
     * document without a value in a field sort.
     */
    public record SortValue(Optional<Number> number, Optional<BytesRef> term) {

        /** The sort value as Lucene's {@code FieldDoc.fields} holds it. */
        static SortValue of(Object value) {
            return switch (value) {
                case null -> new SortValue(Optional.empty(), Optional.empty());
                case Number number -> new SortValue(Optional.of(number), Optional.empty());
                case BytesRef term -> new SortValue(Optional.empty(), Optional.of(BytesRef.deepCopyOf(term)));
                default -> throw new IllegalArgumentException("Unexpected sort value: " + value.getClass());
            };
        }
    }

    /**
     * The stored values of a hit in one field.
     *
     * @param name the field name
     * @param values the values, in the order they were added
     */
    public record HitField(String name, List<IndexDocument.StoredValue> values) {}

    /**
     * A term that More-Like-This chose, with the numbers it chose it by (ADR 0028).
     *
     * @param field the field of the term
     * @param term the term, as {@code MoreLikeThis} has it: a term vector's bytes decoded as UTF-8
     * @param tf how often the term occurs in the source document or text
     * @param docFreq how many documents have the term, deleted ones included
     * @param idf {@code ClassicSimilarity}'s inverse document frequency of the term in the field
     * @param score {@code tf × idf}, which ranks the terms
     */
    public record LikeTerm(String field, String term, int tf, int docFreq, float idf, float score) {}
}
