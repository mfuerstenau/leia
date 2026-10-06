package io.github.mfuerstenau.leia.protocol.terms;

import java.util.List;

/**
 * The terms of one field, from a full scan of its postings. Like the postings statistics, the
 * counts include deleted documents.
 *
 * @param field the field name
 * @param uniqueTerms the number of distinct terms in the field
 * @param topTerms the terms with the highest document frequency, highest first; ties in term order
 */
public record FieldTerms(String field, long uniqueTerms, List<TopTerm> topTerms) {}
