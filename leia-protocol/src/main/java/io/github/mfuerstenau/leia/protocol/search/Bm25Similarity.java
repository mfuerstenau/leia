package io.github.mfuerstenau.leia.protocol.search;

/**
 * {@code BM25Similarity} with its parameters (ADR 0012).
 *
 * @param k1 how quickly a term's frequency saturates, finite and 0 or more (Lucene: 1.2)
 * @param b how much the field length normalizes, from 0 to 1 (Lucene: 0.75)
 */
public record Bm25Similarity(float k1, float b) {}
