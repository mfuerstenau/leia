package io.github.mfuerstenau.leia.protocol.knn;

import java.util.List;

/**
 * Result of {@code getKnnGraphNode} (ADR 0085).
 *
 * @param segment the node's segment
 * @param levels the node's neighbours on each level it is on, from its top level down to 0
 */
public record GetKnnGraphNodeResult(int segment, KnnGraphNode node, List<KnnGraphLevel> levels) {}
