package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A point field's BKD tree, segment by segment, as leia-core's {@code Points} reads it from Lucene
 * 10 (ADR 0041, ADR 0048). Moving through the tree reads only its index, and a leaf's points are one
 * block, so neither needs a job. Values are packed: each dimension's bytes, one after the other.
 */
public interface Points {

    /** The most points {@link #cell} returns of a leaf. */
    int MAX_POINTS = 10_000;

    /**
     * The tree of {@code field} over the whole index.
     *
     * @throws IllegalArgumentException if the index has no such field, or it has no points
     */
    Tree tree(String field) throws IOException;

    /**
     * The cell at {@code path} of {@code field}'s tree in segment {@code segment}: from the root, the
     * position of each child among its siblings.
     *
     * @throws IllegalArgumentException if there is no such segment, the segment has no points in the
     *     field, or there is no cell at {@code path}
     */
    CellContents cell(String field, int segment, List<Integer> path) throws IOException;

    /**
     * A point field over the whole index.
     *
     * @param indexDimensions the first dimensions, which the tree splits on; the cells' corners have
     *     only these
     * @param size the points, deleted documents' included
     * @param docCount the documents with points, summed over the segments
     * @param min the smallest value of each index dimension; empty without points
     * @param max the largest value of each index dimension; empty without points
     * @param segments the segments with points in the field, in reader order
     */
    record Tree(
            String field,
            int dimensions,
            int indexDimensions,
            int bytesPerDimension,
            long size,
            long docCount,
            byte @Nullable [] min,
            byte @Nullable [] max,
            List<Segment> segments) {}

    /**
     * The tree of a field in one segment.
     *
     * @param segment the segment's position among the reader's leaves
     * @param name the segment's name, such as {@code _0}; empty if the leaf isn't a segment reader
     * @param docBase the document id of the segment's first document
     */
    // Never compared, hashed or used as a key (ADR 0088)
    @SuppressWarnings("java:S6218")
    record Segment(
            int segment,
            @Nullable String name,
            int docBase,
            int maxDoc,
            long size,
            int docCount,
            byte[] min,
            byte[] max) {}

    /**
     * A cell of the tree: the points of a box of the value space.
     *
     * @param min the box's smallest value of each index dimension
     * @param max the box's largest value of each index dimension
     * @param leaf whether the cell holds its points itself
     */
    // Never compared, hashed or used as a key (ADR 0088)
    @SuppressWarnings("java:S6218")
    record Cell(byte[] min, byte[] max, long size, boolean leaf) {}

    /**
     * A point of a leaf.
     *
     * @param docId the document id in the index
     */
    // Never compared, hashed or used as a key (ADR 0088)
    @SuppressWarnings("java:S6218")
    record LeafPoint(int docId, boolean deleted, byte[] value) {}

    /**
     * A cell with its children, or, for a leaf, its points.
     *
     * @param children the children in order; empty for a leaf
     * @param points the points of a leaf, by document id, then value; empty for any other cell
     * @param pointsCut whether the leaf has more than {@link #MAX_POINTS} points, and only that many
     *     are listed
     */
    record CellContents(Cell cell, List<Cell> children, List<LeafPoint> points, boolean pointsCut) {}
}
