package io.github.mfuerstenau.leia.protocol.terms;

/**
 * One of the top terms of a field.
 *
 * @param term the term as text; if {@code binary}, its bytes in lowercase hex instead
 * @param binary whether the term's bytes are not valid UTF-8, so {@code term} holds them as hex
 * @param docFreq documents that contain the term
 * @param totalTermFreq occurrences of the term in all documents; equal to {@code docFreq} if
 *     frequencies are not indexed
 */
public record TopTerm(String term, boolean binary, int docFreq, long totalTermFreq) {}
