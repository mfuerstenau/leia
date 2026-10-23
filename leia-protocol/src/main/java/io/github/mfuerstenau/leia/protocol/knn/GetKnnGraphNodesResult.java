package io.github.mfuerstenau.leia.protocol.knn;

import java.util.List;

/**
 * Result of {@code getKnnGraphNodes} (ADR 0085).
 *
 * @param total the nodes on the level
 * @param nodes the page's nodes, in ordinal order
 */
public record GetKnnGraphNodesResult(int total, List<KnnGraphNode> nodes) {}
