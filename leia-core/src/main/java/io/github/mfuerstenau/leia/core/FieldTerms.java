package io.github.mfuerstenau.leia.core;

import java.util.List;
import org.apache.lucene.util.BytesRef;

/**
 * The terms of one field, from a full scan of its postings across all segments. Like the postings
 * statistics, the counts include deleted documents, so a term that only deleted documents have is
 * still counted until a merge removes it.
 *
 * @param field the field name
 * @param uniqueTerms the number of distinct terms in the field
 * @param topTerms the terms with the highest document frequency, highest first; ties in term order
 */
public record FieldTerms(String field, long uniqueTerms, List<TopTerm> topTerms) {

    /**
     * One of the top terms of a field.
     *
     * @param term the term's bytes, which are UTF-8 for text but can be anything
     * @param docFreq documents that contain the term
     * @param totalTermFreq occurrences of the term in all documents; equal to {@code docFreq} if
     *     frequencies are not indexed
     */
    public record TopTerm(BytesRef term, int docFreq, long totalTermFreq) {}
}
