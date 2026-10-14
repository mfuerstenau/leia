package io.github.mfuerstenau.leia.core;

import static io.github.mfuerstenau.leia.core.TestIndexes.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A point field's BKD tree, segment by segment (ADR 0041). */
class PointsTest {

    /** More than one leaf of Lucene's default 512 points. */
    private static final int FIRST_SEGMENT = 2000;

    @TempDir
    Path tmp;

    @Test
    void summarizesTheFieldAndEachSegmentWithPoints() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Points.Tree tree = index.pointTree("number");

            assertThat(List.of(tree.dimensions(), tree.indexDimensions(), tree.bytesPerDimension()))
                    .containsExactly(1, 1, 4);
            // 2000 + 2 points (one document has two), in 2000 + 1 documents.
            assertThat(tree.size()).isEqualTo(FIRST_SEGMENT + 2);
            assertThat(tree.docCount()).isEqualTo(FIRST_SEGMENT + 1);
            assertThat(tree.min().map(PointsTest::value)).contains(-5);
            assertThat(tree.max().map(PointsTest::value)).contains(FIRST_SEGMENT - 1);
            // The segment without the field is left out.
            assertThat(tree.segments())
                    .extracting(
                            Points.Segment::segment,
                            Points.Segment::name,
                            Points.Segment::docBase,
                            Points.Segment::size,
                            segment -> value(segment.min()),
                            segment -> value(segment.max()))
                    .containsExactly(
                            tuple(0, Optional.of("_0"), 0, (long) FIRST_SEGMENT, 0, FIRST_SEGMENT - 1),
                            tuple(2, Optional.of("_2"), FIRST_SEGMENT + 1, 2L, -5, 7));
        }
    }

    @Test
    void walksFromTheRootToALeafAndListsItsPoints() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Points.CellContents root = index.pointCell("number", 0, List.of());

            assertThat(root.cell().leaf()).isFalse();
            assertThat(root.cell().size()).isEqualTo(FIRST_SEGMENT);
            assertThat(root.children()).hasSize(2);
            assertThat(root.children().stream().mapToLong(Points.Cell::size).sum())
                    .isEqualTo(FIRST_SEGMENT);
            assertThat(root.points()).isEmpty();

            // Down the first children to the leftmost leaf: the smallest values, by document id.
            List<Integer> path = new ArrayList<>();
            Points.CellContents cell = root;
            while (!cell.cell().leaf()) {
                path.add(0);
                cell = index.pointCell("number", 0, path);
            }
            assertThat(cell.children()).isEmpty();
            assertThat(cell.points()).hasSize((int) cell.cell().size());
            assertThat(cell.points().getFirst().docId()).isZero();
            assertThat(value(cell.points().getFirst().value())).isZero();
            assertThat(cell.points()).isSortedAccordingTo((a, b) -> Integer.compare(a.docId(), b.docId()));
            assertThat(cell.pointsCut()).isFalse();
            // Document 3 is deleted.
            assertThat(cell.points().get(3).deleted()).isTrue();
            assertThat(cell.points().get(2).deleted()).isFalse();
        }
    }

    @Test
    void aLeafHasItsPointsInDocumentOrderWithGlobalIds() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Points.CellContents root = index.pointCell("number", 2, List.of());

            // Two points: one leaf, its document's points by value.
            assertThat(root.cell().leaf()).isTrue();
            assertThat(root.points())
                    .extracting(Points.LeafPoint::docId, point -> value(point.value()))
                    .containsExactly(tuple(FIRST_SEGMENT + 1, -5), tuple(FIRST_SEGMENT + 1, 7));
        }
    }

    @Test
    void rejectsFieldsWithoutPointsAndSegmentsAndPathsThatDontExist() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThatThrownBy(() -> index.pointTree("id"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Field id has no points");
            assertThatThrownBy(() -> index.pointTree("nope"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No field nope");
            assertThatThrownBy(() -> index.pointCell("number", 3, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No segment 3; the index has 3 segments");
            assertThatThrownBy(() -> index.pointCell("number", 1, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Segment 1 has no points in field number");
            assertThatThrownBy(() -> index.pointCell("number", 0, List.of(2)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No cell at [2] in segment 0");
            assertThatThrownBy(() -> index.pointCell("number", 2, List.of(0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No cell at [0] in segment 2");
        }
    }

    @Test
    void readsTheTreeOfALucene9Index() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(testIndex("lucene9"))) {
            Points.Tree tree = index.pointTree("number");

            assertThat(tree.size()).isEqualTo(3);
            assertThat(index.pointCell("number", 0, List.of()).points())
                    .extracting(Points.LeafPoint::docId, Points.LeafPoint::deleted, point -> value(point.value()))
                    .containsExactly(tuple(0, false, 10), tuple(1, true, 20), tuple(2, false, 30));
        }
    }

    private static int value(BytesRef packed) {
        return IntPoint.decodeDimension(packed.bytes, packed.offset);
    }

    /**
     * Three segments: 2000 documents with the points 0 to 1999, document 3 deleted; one document
     * without the field; one document with the points 7 and -5.
     */
    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("index");
        try (IndexWriter writer = new IndexWriter(
                FSDirectory.open(dir), new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            for (int i = 0; i < FIRST_SEGMENT; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.NO));
                doc.add(new IntPoint("number", i));
                writer.addDocument(doc);
            }
            writer.commit();
            Document plain = new Document();
            plain.add(new StringField("id", "plain", Field.Store.NO));
            writer.addDocument(plain);
            writer.commit();
            Document two = new Document();
            two.add(new StringField("id", "two", Field.Store.NO));
            two.add(new IntPoint("number", 7));
            two.add(new IntPoint("number", -5));
            writer.addDocument(two);
            writer.commit();
            writer.deleteDocuments(new Term("id", "3"));
            writer.commit();
        }
        return dir;
    }
}
