package io.github.mfuerstenau.leia.protocol.points;

import java.util.List;
import java.util.Optional;

/**
 * Result of {@code getPointTree} (ADR 0041): a point field over the whole index, and each segment
 * that has points in it.
 *
 * @param dimensions the dimensions of a point
 * @param indexDimensions the first dimensions, which the tree splits on; the cells' corners have
 *     only these
 * @param bytesPerDimension the bytes of each dimension
 * @param size the points of the field, deleted documents' included
 * @param docCount the documents with points in the field, summed over the segments, deleted ones
 *     included
 * @param min the smallest value of each index dimension over all segments; absent without points
 * @param max the largest value of each index dimension over all segments; absent without points
 * @param segments the segments with points in the field, in reader order
 */
public record GetPointTreeResult(
        String field,
        int dimensions,
        int indexDimensions,
        int bytesPerDimension,
        long size,
        long docCount,
        Optional<PointValue> min,
        Optional<PointValue> max,
        List<PointSegment> segments) {}
