package io.github.mfuerstenau.leia.protocol.search;

/**
 * A term that More-Like-This chose, with the numbers it chose it by (ADR 0028).
 *
 * @param field the field of the term
 * @param term the term as {@code MoreLikeThis} has it, a string: a term vector's bytes are decoded as
 *     UTF-8
 * @param tf how often the term occurs in the source document or text
 * @param docFreq how many documents have the term, deleted ones included
 * @param idf {@code ClassicSimilarity}'s inverse document frequency of the term in the field
 * @param score {@code tf × idf}, which ranks the terms
 */
public record LikeTerm(String field, String term, int tf, int docFreq, float idf, float score) {}
