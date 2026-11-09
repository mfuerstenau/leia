package io.github.mfuerstenau.leia.lucene9;

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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The BKD trees of the Lucene 8.x test index, read with Lucene 9.12 (ADR 0049) through its {@code
 * PointTree}, as leia-core reads Lucene 10's. The writer puts at most 16 points into a leaf, so the
 * trees have several leaves: {@code pt}, of one dimension, has 60 points in each of {@code _0} and
 * {@code _1}; {@code loc}, of two dimensions, 44. Each cell's size is checked against the points of
 * its leaves. The 9.x segment of {@code lucene8to9} has no points.
 */
class Lucene9PointsTest {

    private static RuntimeIndex open(String name) throws IOException {
        String dir = System.getProperty("leia.test.index." + name);
        if (dir == null) {
            throw new IllegalStateException("System property leia.test.index." + name + " is not set");
        }
        return new Lucene9Runtime().open(List.of(Path.of(dir).toAbsolutePath()), DirectoryKind.FS);
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
    @ValueSource(strings = {"lucene8", "lucene8to9"})
    void readsTheTrees(String name) throws IOException {
        try (RuntimeIndex index = open(name)) {
            Points points = index.points().orElseThrow();
            Points.Tree pt = points.tree("pt");
            assertThat(pt.dimensions()).isEqualTo(1);
            assertThat(pt.indexDimensions()).isEqualTo(1);
            assertThat(pt.bytesPerDimension()).isEqualTo(4);
            // 15 points in each document, deleted ones included, document 3 twice.
            assertThat(pt.size()).isEqualTo(120);
            assertThat(pt.docCount()).isEqualTo(8);
            assertThat(pt.min()).satisfies(min -> assertThat(min).isEqualTo(packed(100)));
            assertThat(pt.max()).satisfies(max -> assertThat(max).isEqualTo(packed(714)));
            // Not the 9.x segment, which has no points.
            assertThat(pt.segments())
                    .extracting(
                            Points.Segment::segment,
                            segment -> Optional.ofNullable(segment.name()).orElseThrow(),
                            Points.Segment::docBase,
                            Points.Segment::maxDoc,
                            Points.Segment::size,
                            Points.Segment::docCount)
                    .containsExactly(tuple(0, "_0", 0, 4, 60L, 4), tuple(1, "_1", 4, 4, 60L, 4));
            // The soft update of document 3, first in _1 by the index sort.
            assertThat(pt.segments().get(1).min()).isEqualTo(packed(300));

            Points.Tree loc = points.tree("loc");
            assertThat(loc.dimensions()).isEqualTo(2);
            assertThat(loc.indexDimensions()).isEqualTo(2);
            assertThat(loc.min()).satisfies(min -> assertThat(min).isEqualTo(packed(1, 0)));
            assertThat(loc.max()).satisfies(max -> assertThat(max).isEqualTo(packed(7, 10)));
            assertThat(points.tree("amount").bytesPerDimension()).isEqualTo(8);

            assertThatThrownBy(() -> points.tree("body"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no points");
            assertThatThrownBy(() -> points.tree("nothing")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Each cell's size is that of its children, and a leaf's that of its points; all add up to the tree's. */
    @Test
    void sizesTheCells() throws IOException {
        try (RuntimeIndex index = open("lucene8")) {
            Points points = index.points().orElseThrow();
            for (String field : List.of("pt", "loc", "amount")) {
                for (Points.Segment segment : points.tree(field).segments()) {
                    List<Long> leaves = new ArrayList<>();
                    long size = walk(points, field, segment.segment(), new ArrayList<>(), leaves);
                    assertThat(size)
                            .as("%s in segment %d", field, segment.segment())
                            .isEqualTo(segment.size());
                    assertThat(leaves).allSatisfy(leaf -> assertThat(leaf).isBetween(1L, 16L));
                }
            }
            // A single leaf.
            assertThat(points.cell("amount", 0, List.of()).cell())
                    .extracting(Points.Cell::size, Points.Cell::leaf)
                    .containsExactly(4L, true);
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
    void readsTheLeafPointsWithDeletedOnesMarked() throws IOException {
        try (RuntimeIndex index = open("lucene8")) {
            Points points = index.points().orElseThrow();
            List<Points.LeafPoint> all = new ArrayList<>();
            collect(points, "pt", 0, new ArrayList<>(), all);
            // Document 2 is deleted.
            assertThat(all)
                    .filteredOn(point -> point.docId() == 1)
                    .hasSize(15)
                    .allSatisfy(point -> assertThat(point.deleted()).isTrue());
            assertThat(all)
                    .filteredOn(point -> point.docId() != 1)
                    .allSatisfy(point -> assertThat(point.deleted()).isFalse());
            Points.CellContents first = points.cell("pt", 0, List.of(0, 0));
            assertThat(first.cell().leaf()).isTrue();
            assertThat(first.pointsCut()).isFalse();
            assertThat(first.points())
                    .isSortedAccordingTo((a, b) -> a.docId() != b.docId()
                            ? Integer.compare(a.docId(), b.docId())
                            : Arrays.compareUnsigned(a.value(), b.value()));
            // In _1, the documents' ids are those of the index: document 3's update is 4.
            List<Points.LeafPoint> second = new ArrayList<>();
            collect(points, "pt", 1, new ArrayList<>(), second);
            assertThat(second)
                    .filteredOn(point -> intAt(point.value(), 0) == 300)
                    .extracting(Points.LeafPoint::docId)
                    .containsExactly(4);
        }
    }

    private static void collect(
            Points points, String field, int segment, List<Integer> path, List<Points.LeafPoint> all)
            throws IOException {
        Points.CellContents contents = points.cell(field, segment, List.copyOf(path));
        all.addAll(contents.points());
        for (int i = 0; i < contents.children().size(); i++) {
            path.add(i);
            collect(points, field, segment, path, all);
            path.removeLast();
        }
    }

    @Test
    void refusesWhatIsNotThere() throws IOException {
        try (RuntimeIndex index = open("lucene8to9")) {
            Points points = index.points().orElseThrow();
            assertThatThrownBy(() -> points.cell("pt", 3, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No segment 3");
            // The 9.x segment.
            assertThatThrownBy(() -> points.cell("pt", 2, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no points");
            assertThatThrownBy(() -> points.cell("body", 0, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no points");
            assertThatThrownBy(() -> points.cell("pt", 0, List.of(2)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No cell at [2]");
            assertThatThrownBy(() -> points.cell("amount", 0, List.of(0))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
