package io.github.mfuerstenau.leia.protocol.search;

/**
 * A sort by the score (ADR 0025).
 *
 * @param reverse whether the lowest score comes first; otherwise the highest does
 */
public record ScoreSort(boolean reverse) {}
