package io.github.mfuerstenau.leia.protocol.knn;

/**
 * One of the exact nearest documents that an approximate search missed (ADR 0086).
 *
 * @param rank its place in the exact ranking, from 1
 * @param score its exact score
 */
public record KnnRecallMiss(int rank, int docId, float score) {}
