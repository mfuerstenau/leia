package io.github.mfuerstenau.leia.protocol.points;

/**
 * Params of {@code getPointTree} (ADR 0041): the BKD tree of a point field, segment by segment.
 *
 * @param field a field with points
 */
public record GetPointTreeParams(String field) {}
