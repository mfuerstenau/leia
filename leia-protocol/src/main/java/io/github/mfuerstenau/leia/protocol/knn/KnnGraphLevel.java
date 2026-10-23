package io.github.mfuerstenau.leia.protocol.knn;

import java.util.List;

/**
 * A node's neighbours on one level of its graph (ADR 0085).
 *
 * @param neighbours in the graph's order; empty for the only node of a level
 */
public record KnnGraphLevel(int level, List<KnnGraphNeighbour> neighbours) {}
