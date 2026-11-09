package io.github.mfuerstenau.leia.lucene7;

import io.github.mfuerstenau.leia.runtime.Points;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
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
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.bkd.BKDReader;

/**
 * The BKD trees of point fields, read with Lucene 7.7 (ADR 0048) as leia-core reads Lucene 10's (ADR
 * 0041). 7.7 has no {@code PointTree}: a segment's tree is its {@link BKDReader}, walked through its
 * {@link BKDReader.IndexTree}. A cell's box is the segment's, narrowed by the split value of each
 * cell above it. A cell's size isn't recorded, so it is computed as Lucene 9 computes it for trees
 * written before 8.6 ({@link Walk#size}), which reads one leaf of the segment beyond the cell itself.
 */
final class Lucene7Points implements Points {

    private final IndexReader reader;
    private final FieldInfos fieldInfos;

    Lucene7Points(IndexReader reader, FieldInfos fieldInfos) {
        this.reader = reader;
        this.fieldInfos = fieldInfos;
    }

    @Override
    public Tree tree(String field) throws IOException {
        FieldInfo info = fieldInfos.fieldInfo(field);
        if (info == null) {
            throw new IllegalArgumentException("No field " + field);
        }
        if (info.getPointDataDimensionCount() == 0) {
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
                info.getPointDataDimensionCount(),
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
        if (!(values instanceof BKDReader bkd)) {
            throw new IOException("The points of field " + field + " in segment " + segment
                    + " are no BKD tree of Lucene 6 or 7, but "
                    + values.getClass().getName());
        }
        LeafVisitor visitor = new LeafVisitor(context.docBase, context.reader().getLiveDocs());
        Walk walk = new Walk(bkd, visitor);
        for (int i = 0; i < path.size(); i++) {
            int child = path.get(i);
            if (child < 0 || child > 1 || walk.tree.isLeafNode() || !walk.push(child)) {
                throw new IllegalArgumentException("No cell at " + path.subList(0, i + 1) + " in segment " + segment);
            }
        }
        if (!walk.tree.isLeafNode()) {
            return new CellContents(walk.cell(), children(walk), List.of(), false);
        }
        bkd.visitLeafBlockValues(walk.tree, walk.state);
        List<LeafPoint> points = visitor.points;
        boolean cut = points.size() > MAX_POINTS;
        List<LeafPoint> shown = new ArrayList<>(cut ? points.subList(0, MAX_POINTS) : points);
        shown.sort(Comparator.comparingInt(LeafPoint::docId).thenComparing(LeafPoint::value, Arrays::compareUnsigned));
        return new CellContents(walk.cell(), List.of(), List.copyOf(shown), cut);
    }

    /** The children of the inner node {@code walk} is at. */
    private static List<Cell> children(Walk walk) throws IOException {
        List<Cell> children = new ArrayList<>();
        for (int child = 0; child <= 1; child++) {
            if (walk.push(child)) {
                children.add(walk.cell());
                walk.pop();
            }
        }
        return List.copyOf(children);
    }

    /**
     * A walk through a segment's tree, which knows the box of the cell it is at. Nodes are numbered
     * as in a heap: the root is 1, the children of {@code n} are {@code 2n} and {@code 2n + 1}, and
     * the leaves are the nodes from {@code leafNodeOffset}, the number of leaves, on. In 6.x and 7.x,
     * a tree of more than one dimension is balanced: its leaves are a power of two, at the same depth,
     * and hold {@code maxPointsInLeafNode} or one point less each. A tree of one dimension may instead
     * be written with every leaf full but the last, which then holds what is left, and its leaves may
     * be at two depths.
     */
    private static final class Walk {
        final BKDReader.IntersectState state;
        final BKDReader.IndexTree tree;
        private final BKDReader bkd;
        private final int bytesPerDim;
        private final Deque<byte[][]> boxes = new ArrayDeque<>();
        private byte[] min;
        private byte[] max;
        // What the size of a cell is computed from, found on the first call of size().
        private boolean sized;
        private final int leafNodeOffset;
        private int maxPointsInLeafNode;
        private boolean balanced;
        private int lastLeafNodeID;
        private int lastLeafNodePointCount;

        Walk(BKDReader bkd, LeafVisitor visitor) {
            this.bkd = bkd;
            this.state = bkd.getIntersectState(visitor);
            this.tree = state.index;
            this.bytesPerDim = bkd.getBytesPerDimension();
            this.min = bkd.getMinPackedValue().clone();
            this.max = bkd.getMaxPackedValue().clone();
            // At the root: all of them.
            this.leafNodeOffset = tree.getNumLeaves();
        }

        /**
         * Moves to child {@code side} (0 left, 1 right) of the inner node it is at, if it exists: the
         * left child's box ends at the node's split value, the right child's starts there.
         */
        boolean push(int side) {
            int offset = tree.getSplitDim() * bytesPerDim;
            BytesRef split = tree.getSplitDimValue();
            byte[] childMin = min.clone();
            byte[] childMax = max.clone();
            System.arraycopy(split.bytes, split.offset, side == 0 ? childMax : childMin, offset, bytesPerDim);
            if (side == 0) {
                tree.pushLeft();
            } else {
                tree.pushRight();
            }
            if (!tree.nodeExists()) {
                tree.pop();
                return false;
            }
            boxes.push(new byte[][] {min, max});
            min = childMin;
            max = childMax;
            return true;
        }

        void pop() {
            tree.pop();
            byte[][] box = boxes.pop();
            min = box[0];
            max = box[1];
        }

        Cell cell() throws IOException {
            return new Cell(min.clone(), max.clone(), size(tree.getNodeID()), tree.isLeafNode());
        }

        /** The points under node {@code nodeID}, as Lucene 9's {@code BKDReader} computes them. */
        private long size(int nodeID) throws IOException {
            if (!sized) {
                measure();
            }
            if (leafNodeOffset == 1) {
                return bkd.size();
            }
            int leftMostLeafNode = nodeID;
            while (leftMostLeafNode < leafNodeOffset) {
                leftMostLeafNode = leftMostLeafNode * 2;
            }
            int rightMostLeafNode = nodeID;
            while (rightMostLeafNode < leafNodeOffset) {
                rightMostLeafNode = rightMostLeafNode * 2 + 1;
            }
            if (balanced) {
                return sizeFromBalancedTree(leftMostLeafNode, rightMostLeafNode);
            }
            // The leaves are at most two levels apart: if the left one is a level deeper, it is not
            // in the same row as the right one.
            int numLeaves = rightMostLeafNode >= leftMostLeafNode
                    ? rightMostLeafNode - leftMostLeafNode + 1
                    : rightMostLeafNode - leftMostLeafNode + 1 + leafNodeOffset;
            return rightMostLeafNode == lastLeafNodeID
                    ? (long) (numLeaves - 1) * maxPointsInLeafNode + lastLeafNodePointCount
                    : (long) numLeaves * maxPointsInLeafNode;
        }

        /**
         * Finds the number of leaves, the most points of a leaf and whether the tree is balanced, as
         * Lucene 9's {@code BKDReader.isTreeBalanced} does: a tree of one dimension whose leaves are a
         * power of two is balanced unless its last leaf holds what is left of the points.
         */
        private void measure() throws IOException {
            sized = true;
            // Every leaf counted full; 7.7 doesn't say how many points a leaf holds at most otherwise.
            maxPointsInLeafNode = Math.toIntExact(bkd.estimatePointCount(new AllInside()) / leafNodeOffset);
            lastLeafNodeID = 1;
            while (lastLeafNodeID < leafNodeOffset) {
                lastLeafNodeID = lastLeafNodeID * 2 + 1;
            }
            int remainder = Math.toIntExact(bkd.size() % maxPointsInLeafNode);
            lastLeafNodePointCount = remainder == 0 ? maxPointsInLeafNode : remainder;
            if (leafNodeOffset == 1) {
                balanced = true;
            } else if (bkd.getNumIndexDimensions() > 1) {
                balanced = true;
            } else if (Integer.bitCount(leafNodeOffset) != 1) {
                balanced = false;
            } else {
                LeafVisitor counter = new LeafVisitor(0, null);
                BKDReader.IntersectState last = bkd.getIntersectState(counter);
                while (!last.index.isLeafNode()) {
                    last.index.pushRight();
                }
                bkd.visitLeafBlockValues(last.index, last);
                balanced = counter.count != remainder;
            }
        }

        /**
         * The points of the leaves from {@code leftMostLeafNode} to {@code rightMostLeafNode} of a
         * balanced tree: as Lucene 9's {@code sizeFromBalancedTree}, the points that leave a leaf one
         * short of {@code maxPointsInLeafNode} are spread over the leaves as the writer split them.
         */
        private long sizeFromBalancedTree(int leftMostLeafNode, int rightMostLeafNode) {
            int extraPoints = Math.toIntExact((long) maxPointsInLeafNode * leafNodeOffset - bkd.size());
            int nodeOffset = leafNodeOffset - extraPoints;
            long count = 0;
            for (int node = leftMostLeafNode; node <= rightMostLeafNode; node++) {
                if (balanceTreeNodePosition(0, leafNodeOffset, node - leafNodeOffset, 0, 0) < nodeOffset) {
                    count += maxPointsInLeafNode;
                } else {
                    count += maxPointsInLeafNode - 1;
                }
            }
            return count;
        }

        private static int balanceTreeNodePosition(int minNode, int maxNode, int node, int position, int level) {
            if (maxNode - minNode == 1) {
                return position;
            }
            int mid = (minNode + maxNode + 1) >>> 1;
            if (mid > node) {
                return balanceTreeNodePosition(minNode, mid, node, position, level + 1);
            }
            return balanceTreeNodePosition(mid, maxNode, node, position + (1 << level), level + 1);
        }
    }

    /** Collects the points of a leaf, with their documents' ids in the index. */
    private static final class LeafVisitor implements PointValues.IntersectVisitor {
        final List<LeafPoint> points = new ArrayList<>();
        int count;
        private final int docBase;
        private final Bits liveDocs;

        LeafVisitor(int docBase, Bits liveDocs) {
            this.docBase = docBase;
            this.liveDocs = liveDocs;
        }

        @Override
        public void visit(int docID) {
            throw new IllegalStateException("A point without its value");
        }

        @Override
        public void visit(int docID, byte[] packedValue) {
            count++;
            if (points.size() <= MAX_POINTS) {
                points.add(
                        new LeafPoint(docBase + docID, liveDocs != null && !liveDocs.get(docID), packedValue.clone()));
            }
        }

        /** Crosses every cell, so that a leaf gives its values. */
        @Override
        public PointValues.Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
            return PointValues.Relation.CELL_CROSSES_QUERY;
        }
    }

    /** Takes every cell as inside, so that {@code estimatePointCount} counts every leaf full. */
    private static final class AllInside implements PointValues.IntersectVisitor {
        @Override
        public void visit(int docID) {
            // Not called: only estimatePointCount, which visits no documents, uses this visitor.
        }

        @Override
        public void visit(int docID, byte[] packedValue) {
            // Not called: only estimatePointCount, which visits no documents, uses this visitor.
        }

        @Override
        public PointValues.Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
            return PointValues.Relation.CELL_INSIDE_QUERY;
        }
    }

    private static Optional<String> segmentName(LeafReader leaf) {
        return FilterLeafReader.unwrap(leaf) instanceof SegmentReader segment
                ? Optional.of(segment.getSegmentName())
                : Optional.empty();
    }
}
