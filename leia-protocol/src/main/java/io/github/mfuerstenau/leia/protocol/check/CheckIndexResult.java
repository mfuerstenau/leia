package io.github.mfuerstenau.leia.protocol.check;

import java.util.List;

/**
 * The outcome of a check of every index of the open path (ADR 0022).
 *
 * @param indexes each index checked: the opened ones in order, then the skipped ones
 * @param tookMillis how long the whole check took
 */
public record CheckIndexResult(List<IndexCheck> indexes, long tookMillis) {}
