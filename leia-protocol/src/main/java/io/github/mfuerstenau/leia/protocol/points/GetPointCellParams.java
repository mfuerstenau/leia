package io.github.mfuerstenau.leia.protocol.points;

import java.util.List;

/**
 * Params of {@code getPointCell} (ADR 0041): a cell of a point field's BKD tree in one segment.
 *
 * @param field a field with points
 * @param segment the segment, as {@link PointSegment#segment()} gives it
 * @param path the child positions from the segment's root cell, from 0: {@code []} is the root,
 *     {@code [0, 1]} its first child's second child
 */
public record GetPointCellParams(String field, int segment, List<Integer> path) {}
