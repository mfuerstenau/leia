package io.github.mfuerstenau.leia.core;

import java.time.Duration;
import java.util.List;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.util.BytesRef;
import org.jspecify.annotations.Nullable;

/**
 * One page of the hits of a search (ADR 0010).
 *
 * @param query the query as Lucene writes it ({@code Query.toString()})
 * @param rewrittenQuery the query after rewriting, as Lucene writes it
 * @param totalHits how many documents match: exactly, or at least this many if counting stopped
 * @param hits the hits of the page, best first
 * @param took how long the search took
 * @param moreLikeThisTerms for a More-Like-This query, the terms it chose, best first; null for any other query (ADR 0028)
 */
public record SearchResults(
        String query,
        String rewrittenQuery,
        TotalHits totalHits,
        List<Hit> hits,
        Duration took,
        @Nullable List<LikeTerm> moreLikeThisTerms) {

    /**
     * A matching document.
     *
     * @param docId the document id in the whole index
     * @param score the document's score for the query, also when the hits are sorted by fields
     * @param sortValues the values the hit was sorted by, one per sort field; empty without a sort
     * @param fields the document's stored values in the fields asked for, in the order of each
     *     field's first value
     * @param highlights the best passages per field with the query's matches, in the order of {@code
     *     fields}; empty without highlighting (ADR 0042)
     */
    public record Hit(
            int docId,
            float score,
            List<SortValue> sortValues,
            List<HitField> fields,
            List<FieldHighlight> highlights) {}

    /**
     * The best passages of a hit in one field (ADR 0042).
     *
     * @param field the field name
     * @param offsetSource where the offsets of the matches came from
     * @param passages the passages with at least one match, in the order of the values and their text
     */
    public record FieldHighlight(String field, OffsetSource offsetSource, List<Passage> passages) {}

    /**
     * A passage of a stored value with matches in it. Positions are UTF-8 byte offsets into the whole
     * value.
     *
     * @param valueIndex the value's position among the document's stored values in the field
     * @param start where {@code text} starts in the value
     * @param text the passage, cut around its first match if it is long
     * @param spans the matches in {@code text}, in order and not overlapping
     * @param score how well the passage matches
     */
    public record Passage(int valueIndex, int start, String text, List<StoredScan.Span> spans, float score) {}

    /**
     * The value a hit was sorted by in one sort field (ADR 0012, ADR 0025): a number (of a numeric
     * field, a score or a document id), or a term of a {@code STRING} sort. Both are null for a
     * document without a value in a field sort.
     */
    public record SortValue(
            @Nullable Number number, @Nullable BytesRef term) {

        /** The sort value as Lucene's {@code FieldDoc.fields} holds it. */
        static SortValue of(@Nullable Object value) {
            return switch (value) {
                case null -> new SortValue(null, null);
                case Number number -> new SortValue(number, null);
                case BytesRef term -> new SortValue(null, BytesRef.deepCopyOf(term));
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
