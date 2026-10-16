package io.github.mfuerstenau.leia.lucene7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Points;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The BKD trees of the Lucene 6.x and 7.x test indexes, read with Lucene 7.7 (ADR 0048). The writers
 * put at most 16 points into a leaf, so the trees have several leaves: {@code pt}, of one dimension,
 * has 60 points in {@code _0} (leaves of 16, 16, 16 and 12) and 45 in the {@code _1} of 6.x (three
 * leaves of 16, 16 and 13, at two depths); {@code loc}, of two dimensions, 44 in {@code _0} (four
 * leaves of 11) and 33 in the {@code _1} of 6.x (four leaves of 9, 8, 8 and 8). The sizes of the cells
 * are computed, not recorded, so each is checked against the points of its leaves.
 */
class Lucene7PointsTest {

    private static RuntimeIndex open(String name) throws IOException {
        String dir = System.getProperty("leia.test.index." + name);
        if (dir == null) {
            throw new IllegalStateException("System property leia.test.index." + name + " is not set");
        }
        return new Lucene7Runtime().open(List.of(Path.of(dir).toAbsolutePath()), DirectoryKind.FS);
    }

    /** An int as a point packs it: big-endian, with the sign bit flipped. */
    private static byte[] packed(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Integer.BYTES);
        for (int value : values) {
            buffer.putInt(value ^ Integer.MIN_VALUE);
        }
        return buffer.array();
    }

    private static int intAt(byte[] packed, int dimension) {
        return ByteBuffer.wrap(packed).getInt(dimension * Integer.BYTES) ^ Integer.MIN_VALUE;
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void readsTheTrees(String name) throws IOException {
        boolean seven = name.equals("lucene7");
        try (RuntimeIndex index = open(name)) {
            Points points = index.points().orElseThrow();
            Points.Tree pt = points.tree("pt");
            assertThat(pt.dimensions()).isEqualTo(1);
            assertThat(pt.indexDimensions()).isEqualTo(1);
            assertThat(pt.bytesPerDimension()).isEqualTo(4);
            // 15 points in each document, deleted ones included; 7.x has document 3 twice.
            assertThat(pt.size()).isEqualTo(seven ? 120 : 105);
            assertThat(pt.docCount()).isEqualTo(seven ? 8 : 7);
            assertThat(pt.min()).hasValueSatisfying(min -> assertThat(min).isEqualTo(packed(100)));
            assertThat(pt.max()).hasValueSatisfying(max -> assertThat(max).isEqualTo(packed(714)));
            assertThat(pt.segments())
                    .extracting(
                            Points.Segment::segment,
                            segment -> segment.name().orElseThrow(),
                            Points.Segment::docBase,
                            Points.Segment::maxDoc,
                            Points.Segment::size,
                            Points.Segment::docCount)
                    .containsExactly(
                            tuple(0, "_0", 0, 4, 60L, 4),
                            seven ? tuple(1, "_1", 4, 4, 60L, 4) : tuple(1, "_1", 4, 3, 45L, 3));
            assertThat(pt.segments().get(1).min()).isEqualTo(packed(seven ? 300 : 500));

            Points.Tree loc = points.tree("loc");
            assertThat(loc.dimensions()).isEqualTo(2);
            assertThat(loc.indexDimensions()).isEqualTo(2);
            assertThat(loc.min()).hasValueSatisfying(min -> assertThat(min).isEqualTo(packed(1, 0)));
            assertThat(loc.max()).hasValueSatisfying(max -> assertThat(max).isEqualTo(packed(7, 10)));
            assertThat(points.tree("amount").bytesPerDimension()).isEqualTo(8);

            assertThatThrownBy(() -> points.tree("body"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no points");
            assertThatThrownBy(() -> points.tree("nothing")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Each cell's size is that of its children, and a leaf's that of its points; all add up to the tree's. */
    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void computesTheSizesOfTheCells(String name) throws IOException {
        try (RuntimeIndex index = open(name)) {
            Points points = index.points().orElseThrow();
            for (String field : List.of("pt", "loc", "amount")) {
                for (Points.Segment segment : points.tree(field).segments()) {
                    List<Long> leaves = new ArrayList<>();
                    long size = walk(points, field, segment.segment(), new ArrayList<>(), leaves);
                    assertThat(size)
                            .as("%s in segment %d", field, segment.segment())
                            .isEqualTo(segment.size());
                }
            }
        }
    }

    /** Checks the cell at {@code path} and those below it; returns the points of its leaves. */
    private static long walk(Points points, String field, int segment, List<Integer> path, List<Long> leaves)
            throws IOException {
        Points.CellContents contents = points.cell(field, segment, List.copyOf(path));
        Points.Cell cell = contents.cell();
        if (cell.leaf()) {
            assertThat(contents.children()).isEmpty();
            assertThat(contents.points()).hasSize(Math.toIntExact(cell.size()));
            for (Points.LeafPoint point : contents.points()) {
                assertThat(Arrays.compareUnsigned(point.value(), cell.min())).isNotNegative();
                assertThat(Arrays.compareUnsigned(point.value(), cell.max())).isNotPositive();
            }
            leaves.add(cell.size());
            return cell.size();
        }
        assertThat(contents.points()).isEmpty();
        long sum = 0;
        for (int i = 0; i < contents.children().size(); i++) {
            path.add(i);
            long child = walk(points, field, segment, path, leaves);
            path.removeLast();
            assertThat(contents.children().get(i).size()).isEqualTo(child);
            sum += child;
        }
        assertThat(cell.size()).as("%s %s in segment %d", field, path, segment).isEqualTo(sum);
        return sum;
    }

    @Test
    void readsBalancedAndUnbalancedTrees() throws IOException {
        try (RuntimeIndex index = open("lucene6")) {
            Points points = index.points().orElseThrow();
            // Every leaf full but the last, all at one depth.
            assertThat(leafSizes(points, "pt", 0)).containsExactly(16L, 16L, 16L, 12L);
            // Three leaves, at two depths: the root's right child is a leaf.
            assertThat(leafSizes(points, "pt", 1)).containsExactly(16L, 16L, 13L);
            Points.CellContents root = points.cell("pt", 1, List.of());
            assertThat(root.children()).extracting(Points.Cell::leaf).containsExactly(false, true);
            // Balanced, with 11 points in each leaf, or with 9 or 8.
            assertThat(leafSizes(points, "loc", 0)).containsExactly(11L, 11L, 11L, 11L);
            assertThat(leafSizes(points, "loc", 1)).containsExactly(9L, 8L, 8L, 8L);
            // A single leaf.
            assertThat(points.cell("amount", 0, List.of()).cell())
                    .extracting(Points.Cell::size, Points.Cell::leaf)
                    .containsExactly(4L, true);
        }
    }

    private static List<Long> leafSizes(Points points, String field, int segment) throws IOException {
        List<Long> leaves = new ArrayList<>();
        walk(points, field, segment, new ArrayList<>(), leaves);
        return leaves;
    }

    @Test
    void narrowsTheBoxesBySplitValue() throws IOException {
        try (RuntimeIndex index = open("lucene6")) {
            Points points = index.points().orElseThrow();
            Points.CellContents root = points.cell("pt", 0, List.of());
            assertThat(root.cell().leaf()).isFalse();
            assertThat(root.cell().min()).isEqualTo(packed(100));
            assertThat(root.cell().max()).isEqualTo(packed(414));
            Points.Cell left = root.children().get(0);
            Points.Cell right = root.children().get(1);
            // The left child ends where the right one starts: at the split value.
            assertThat(left.min()).isEqualTo(root.cell().min());
            assertThat(left.max()).isEqualTo(right.min());
            assertThat(right.max()).isEqualTo(root.cell().max());
            // Two dimensions: only the split dimension narrows.
            Points.CellContents loc = points.cell("loc", 0, List.of(0));
            assertThat(intAt(loc.cell().min(), 0)).isEqualTo(1);
            assertThat(intAt(loc.cell().max(), 0)).isEqualTo(4);
            assertThat(intAt(loc.children().get(0).max(), 1))
                    .isEqualTo(intAt(loc.children().get(1).min(), 1));
        }
    }

    @Test
    void readsTheLeafPointsWithDeletedOnesMarked() throws IOException {
        try (RuntimeIndex index = open("lucene6")) {
            Points points = index.points().orElseThrow();
            // The second leaf of _0: 201 to 214 of document 2 (deleted), then 300 and 301 of document 3.
            Points.CellContents leaf = points.cell("pt", 0, List.of(0, 1));
            assertThat(leaf.cell().leaf()).isTrue();
            assertThat(leaf.pointsCut()).isFalse();
            assertThat(leaf.children()).isEmpty();
            assertThat(leaf.points())
                    .extracting(Points.LeafPoint::docId, Points.LeafPoint::deleted, point -> intAt(point.value(), 0))
                    .startsWith(tuple(1, true, 201), tuple(1, true, 202))
                    .contains(tuple(1, true, 214));
            assertThat(leaf.points())
                    .isSortedAccordingTo((a, b) -> a.docId() != b.docId()
                            ? Integer.compare(a.docId(), b.docId())
                            : Arrays.compareUnsigned(a.value(), b.value()));
            // In _1, the documents' ids are those of the index.
            assertThat(points.cell("pt", 1, List.of(1)).points())
                    .extracting(Points.LeafPoint::docId, Points.LeafPoint::deleted)
                    .containsOnly(tuple(6, false));
            Points.CellContents corner = points.cell("loc", 1, List.of(0, 0));
            assertThat(corner.points()).hasSize(9);
            assertThat(corner.points())
                    .allSatisfy(point -> assertThat(point.value()).hasSize(8));
        }
    }

    @Test
    void refusesWhatIsNotThere() throws IOException {
        try (RuntimeIndex index = open("lucene7")) {
            Points points = index.points().orElseThrow();
            assertThatThrownBy(() -> points.cell("pt", 2, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No segment 2");
            assertThatThrownBy(() -> points.cell("pt", -1, List.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> points.cell("body", 0, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no points");
            assertThatThrownBy(() -> points.cell("pt", 0, List.of(2)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No cell at [2]");
            // Below a leaf.
            assertThatThrownBy(() -> points.cell("pt", 0, List.of(0, 0, 0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No cell at [0, 0, 0]");
            assertThatThrownBy(() -> points.cell("amount", 0, List.of(0))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
