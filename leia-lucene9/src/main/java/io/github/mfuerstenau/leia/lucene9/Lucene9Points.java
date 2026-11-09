package io.github.mfuerstenau.leia.lucene9;

import io.github.mfuerstenau.leia.runtime.Points;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.util.Bits;

/**
 * The BKD trees of point fields, read with Lucene 9.12 (ADR 0049) as leia-core reads Lucene 10's (ADR
 * 0041): 9.12 has the same {@link PointValues.PointTree}, with each cell's box and size. For a tree
 * that 8.x wrote before 8.6, which records no sizes, 9.12's {@code BKDReader} computes them itself.
 */
final class Lucene9Points implements Points {

    private final IndexReader reader;
    private final FieldInfos fieldInfos;

    Lucene9Points(IndexReader reader, FieldInfos fieldInfos) {
        this.reader = reader;
        this.fieldInfos = fieldInfos;
    }

    @Override
    public Tree tree(String field) throws IOException {
        FieldInfo info = fieldInfos.fieldInfo(field);
        if (info == null) {
            throw new IllegalArgumentException("No field " + field);
        }
        if (info.getPointDimensionCount() == 0) {
            throw new IllegalArgumentException("The field " + field + " has no points");
        }
        List<Segment> segments = new ArrayList<>();
        for (LeafReaderContext context : reader.leaves()) {
            PointValues values = context.reader().getPointValues(field);
            if (values == null || values.size() == 0) {
                continue;
            }
            segments.add(new Segment(
                    context.ord,
                    segmentName(context.reader()).orElse(null),
                    context.docBase,
                    context.reader().maxDoc(),
                    values.size(),
                    values.getDocCount(),
                    values.getMinPackedValue().clone(),
                    values.getMaxPackedValue().clone()));
        }
        return new Tree(
                field,
                info.getPointDimensionCount(),
                info.getPointIndexDimensionCount(),
                info.getPointNumBytes(),
                segments.stream().mapToLong(Segment::size).sum(),
                segments.stream().mapToLong(Segment::docCount).sum(),
                Optional.ofNullable(PointValues.getMinPackedValue(reader, field))
                        .map(byte[]::clone)
                        .orElse(null),
                Optional.ofNullable(PointValues.getMaxPackedValue(reader, field))
                        .map(byte[]::clone)
                        .orElse(null),
                List.copyOf(segments));
    }

    @Override
    public CellContents cell(String field, int segment, List<Integer> path) throws IOException {
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
                            docBase + docID, liveDocs != null && !liveDocs.get(docID), packedValue.clone()));
                }
            }

            @Override
            public PointValues.Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
                return PointValues.Relation.CELL_CROSSES_QUERY;
            }
        });
        boolean cut = points.size() > MAX_POINTS;
        List<LeafPoint> shown = new ArrayList<>(cut ? points.subList(0, MAX_POINTS) : points);
        shown.sort(Comparator.comparingInt(LeafPoint::docId).thenComparing(LeafPoint::value, Arrays::compareUnsigned));
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
                tree.getMinPackedValue().clone(), tree.getMaxPackedValue().clone(), tree.size(), leaf);
    }

    private static Optional<String> segmentName(LeafReader leaf) {
        return FilterLeafReader.unwrap(leaf) instanceof SegmentReader segment
                ? Optional.of(segment.getSegmentName())
                : Optional.empty();
    }
}
