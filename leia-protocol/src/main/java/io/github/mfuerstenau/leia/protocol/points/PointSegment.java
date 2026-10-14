package io.github.mfuerstenau.leia.protocol.points;

import java.util.Optional;

/**
 * The BKD tree of a point field in one segment (ADR 0041): its root cell.
 *
 * @param segment the segment's position in the open reader, from 0: the handle {@code
 *     getPointCell} takes, valid until the index is reopened
 * @param index the position of the segment's index directory among those opened (ADR 0021), from 0
 * @param name the segment's name, such as {@code _0}; absent if the reader doesn't say
 * @param docBase the document id of the segment's first document
 * @param maxDoc the documents of the segment, deleted ones included
 * @param size the points of the field in the segment
 * @param docCount the documents with points in the field in the segment
 * @param min the smallest value of each index dimension in the segment
 * @param max the largest value of each index dimension in the segment
 */
public record PointSegment(
        int segment,
        int index,
        Optional<String> name,
        int docBase,
        int maxDoc,
        long size,
        int docCount,
        PointValue min,
        PointValue max) {}
