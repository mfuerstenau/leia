package io.github.mfuerstenau.leia.protocol.search;

/**
 * A sort by the document id (ADR 0025).
 *
 * @param reverse whether the highest id comes first
 */
public record DocSort(boolean reverse) {}
