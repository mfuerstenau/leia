package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.jspecify.annotations.Nullable;

/**
 * A point field's BKD tree, segment by segment (ADR 0041). Moving through the tree reads only its
 * index, and a leaf's points are one block, so neither needs a job.
 */
public final class Points {

    /** The most points {@link #cell} returns of a leaf. */
    public static final int MAX_POINTS = 10_000;

    /**
     * A point field over the whole index.
     *
     * @param indexDimensions the first dimensions, which the tree splits on; the cells' corners have
     *     only these
     * @param size the points, deleted documents' included
     * @param docCount the documents with points, summed over the segments
     * @param min the smallest value of each index dimension, packed; null without points
     * @param max the largest value of each index dimension, packed; null without points
     * @param segments the segments with points in the field, in reader order
     */
    public record Tree(
            String field,
            int dimensions,
            int indexDimensions,
            int bytesPerDimension,
            long size,
            long docCount,
            @Nullable BytesRef min,
            @Nullable BytesRef max,
            List<Segment> segments) {}

    /**
     * The tree of a field in one segment.
     *
     * @param segment the segment's position among the reader's leaves
     * @param name the segment's name, such as {@code _0}; null if the leaf isn't a segment reader
     * @param docBase the document id of the segment's first document
     */
    public record Segment(
            int segment,
            @Nullable String name,
            int docBase,
            int maxDoc,
            long size,
            int docCount,
            BytesRef min,
            BytesRef max) {}

    /**
     * A cell of the tree: the points of a box of the value space.
     *
     * @param min the box's smallest value of each index dimension, packed
     * @param max the box's largest value of each index dimension, packed
     * @param leaf whether the cell holds its points itself
     */
    public record Cell(BytesRef min, BytesRef max, long size, boolean leaf) {}

    /**
     * A point of a leaf.
     *
     * @param docId the document id in the index
     * @param value the point, packed
     */
    public record LeafPoint(int docId, boolean deleted, BytesRef value) {}

    /**
     * A cell with its children, or, for a leaf, its points.
     *
     * @param children the children in order; empty for a leaf
     * @param points the points of a leaf, by document id, then value; empty for any other cell
     * @param pointsCut whether the leaf has more than {@link #MAX_POINTS} points, and only that many
     *     are listed
     */
    public record CellContents(Cell cell, List<Cell> children, List<LeafPoint> points, boolean pointsCut) {}

    private Points() {}

    static Tree tree(IndexReader reader, FieldInfo field) throws IOException {
        String name = field.getName();
        List<Segment> segments = new ArrayList<>();
        for (LeafReaderContext context : reader.leaves()) {
            PointValues values = context.reader().getPointValues(name);
            if (values == null || values.size() == 0) {
                continue;
            }
            segments.add(new Segment(
                    context.ord,
                    segmentName(context.reader()),
                    context.docBase,
                    context.reader().maxDoc(),
                    values.size(),
                    values.getDocCount(),
                    new BytesRef(values.getMinPackedValue().clone()),
                    new BytesRef(values.getMaxPackedValue().clone())));
        }
        byte[] min = PointValues.getMinPackedValue(reader, name);
        byte[] max = PointValues.getMaxPackedValue(reader, name);
        return new Tree(
                name,
                field.getPointDimensionCount(),
                field.getPointIndexDimensionCount(),
                field.getPointNumBytes(),
                segments.stream().mapToLong(Segment::size).sum(),
                segments.stream().mapToLong(Segment::docCount).sum(),
                min != null ? new BytesRef(min) : null,
                max != null ? new BytesRef(max) : null,
                List.copyOf(segments));
    }

    /**
     * The cell at {@code path} of a field's tree in segment {@code segment}.
     *
     * @throws IllegalArgumentException if there is no such segment, the segment has no points in the
     *     field, or there is no cell at {@code path}
     */
    static CellContents cell(IndexReader reader, String field, int segment, List<Integer> path) throws IOException {
        List<LeafReaderContext> leaves = reader.leaves();
        if (segment < 0 || segment >= leaves.size()) {
            throw new IllegalArgumentException(
                    "No segment " + segment + "; the index has " + leaves.size() + " segments");
        }
        LeafReaderContext context = leaves.get(segment);
        PointValues values = context.reader().getPointValues(field);
        if (values == null || values.size() == 0) {
            throw new IllegalArgumentException("Segment " + segment + " has no points in field " + field);
        }
        PointValues.PointTree tree = values.getPointTree();
        moveTo(tree, path, segment);
        List<Cell> children = new ArrayList<>();
        boolean leaf = !tree.moveToChild();
        if (!leaf) {
            do {
                children.add(cell(tree));
            } while (tree.moveToSibling());
            tree.moveToParent();
            return new CellContents(cell(tree, false), List.copyOf(children), List.of(), false);
        }
        List<LeafPoint> points = new ArrayList<>();
        Bits liveDocs = context.reader().getLiveDocs();
        int docBase = context.docBase;
        tree.visitDocValues(new PointValues.IntersectVisitor() {
            @Override
            public void visit(int docID) {
                throw new IllegalStateException("A point without its value");
            }

            @Override
            public void visit(int docID, byte[] packedValue) {
                if (points.size() <= MAX_POINTS) {
                    points.add(new LeafPoint(
                            docBase + docID,
                            liveDocs != null && !liveDocs.get(docID),
                            new BytesRef(packedValue.clone())));
                }
            }

            @Override
            public PointValues.Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
                return PointValues.Relation.CELL_CROSSES_QUERY;
            }
        });
        boolean cut = points.size() > MAX_POINTS;
        List<LeafPoint> shown = new ArrayList<>(cut ? points.subList(0, MAX_POINTS) : points);
        shown.sort(Comparator.comparingInt(LeafPoint::docId).thenComparing(LeafPoint::value));
        return new CellContents(cell(tree, true), List.of(), List.copyOf(shown), cut);
    }

    /** Moves {@code tree} down {@code path}, the index of a child at each level. */
    private static void moveTo(PointValues.PointTree tree, List<Integer> path, int segment) throws IOException {
        for (int i = 0; i < path.size(); i++) {
            int child = path.get(i);
            boolean found = child >= 0 && tree.moveToChild();
            for (int n = 0; found && n < child; n++) {
                found = tree.moveToSibling();
            }
            if (!found) {
                throw new IllegalArgumentException("No cell at " + path.subList(0, i + 1) + " in segment " + segment);
            }
        }
    }

    /** The cell the tree is at, and whether it is a leaf; the tree stays there. */
    private static Cell cell(PointValues.PointTree tree) throws IOException {
        boolean leaf = !tree.moveToChild();
        if (!leaf) {
            tree.moveToParent();
        }
        return cell(tree, leaf);
    }

    private static Cell cell(PointValues.PointTree tree, boolean leaf) {
        return new Cell(
                new BytesRef(tree.getMinPackedValue().clone()),
                new BytesRef(tree.getMaxPackedValue().clone()),
                tree.size(),
                leaf);
    }

    private static @Nullable String segmentName(LeafReader leaf) {
        return FilterLeafReader.unwrap(leaf) instanceof SegmentReader segment ? segment.getSegmentName() : null;
    }
}
