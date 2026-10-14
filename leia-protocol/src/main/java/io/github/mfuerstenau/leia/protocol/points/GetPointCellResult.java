package io.github.mfuerstenau.leia.protocol.points;

import java.util.List;

/**
 * Result of {@code getPointCell} (ADR 0041).
 *
 * @param cell the cell
 * @param children the cell's children, in order; empty for a leaf
 * @param points the points of a leaf, by document id, then value; empty for any other cell
 * @param pointsCut whether the leaf has more than {@link PointCell#MAX_POINTS} points, and only
 *     that many are listed
 */
public record GetPointCellResult(PointCell cell, List<PointCell> children, List<LeafPoint> points, boolean pointsCut) {}
