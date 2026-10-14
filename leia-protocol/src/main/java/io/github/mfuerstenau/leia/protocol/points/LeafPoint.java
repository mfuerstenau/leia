package io.github.mfuerstenau.leia.protocol.points;

/**
 * A point of a leaf cell (ADR 0041).
 *
 * @param docId the document id in the open index
 * @param deleted whether the document is deleted
 * @param value the point, all of its dimensions
 */
public record LeafPoint(int docId, boolean deleted, PointValue value) {}
