package io.github.mfuerstenau.leia.protocol.points;

import java.util.List;

/**
 * A point, or a corner of a cell of points (ADR 0041). The index doesn't record what a point
 * encodes, such as an int, a double or a latitude and longitude, so it travels as bytes.
 *
 * @param dimensions the bytes of each dimension in lowercase hex, as Lucene packs them: sortable
 *     and big-endian, for {@code IntPoint} and the like
 */
public record PointValue(List<String> dimensions) {}
