package io.github.mfuerstenau.leia.protocol.knn;

import java.util.List;
import java.util.OptionalInt;

/**
 * The levels of a segment's HNSW graph (ADR 0085).
 *
 * @param levelSizes the nodes on each level, from level 0, which has every node, up
 * @param entry the node a search starts from, on the top level
 * @param maxConn the most neighbours of a node on the levels above 0, and half of those on level 0
 *     (Lucene's M); absent if the runtime's Lucene doesn't say, as 9.12 doesn't
 */
public record KnnGraphShape(List<Integer> levelSizes, KnnGraphNode entry, OptionalInt maxConn) {}
