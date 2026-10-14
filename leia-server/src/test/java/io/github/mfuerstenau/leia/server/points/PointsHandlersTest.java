package io.github.mfuerstenau.leia.server.points;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.DirectoryImpl;
import io.github.mfuerstenau.leia.core.DirectoryOnlyIndex;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellResult;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeResult;
import io.github.mfuerstenau.leia.protocol.points.LeafPoint;
import io.github.mfuerstenau.leia.protocol.points.PointCell;
import io.github.mfuerstenau.leia.protocol.points.PointSegment;
import io.github.mfuerstenau.leia.protocol.points.PointValue;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code getPointTree} and {@code getPointCell} (ADR 0041). */
class PointsHandlersTest {

    @TempDir
    Path tmp;

    private final Jobs jobs = new Jobs();
    private final CurrentIndex currentIndex = new CurrentIndex(jobs, new Filters());
    private final GetPointTreeHandler tree = new GetPointTreeHandler(currentIndex);
    private final GetPointCellHandler cell = new GetPointCellHandler(currentIndex);

    @Test
    void describesTheTreeAndALeafAsHexPerDimension() throws Exception {
        currentIndex.replace(ReadOnlyIndex.open(writeIndex()));

        GetPointTreeResult result = tree.handle(new GetPointTreeParams("xy"));

        PointValue min = new PointValue(List.of("80000001", "7ffffffe"));
        PointValue max = new PointValue(List.of("80000002", "7fffffff"));
        assertThat(result)
                .isEqualTo(new GetPointTreeResult(
                        "xy",
                        2,
                        2,
                        4,
                        2,
                        2,
                        Optional.of(min),
                        Optional.of(max),
                        List.of(new PointSegment(0, 0, Optional.of("_0"), 0, 2, 2, 2, min, max))));
        assertThat(cell.handle(new GetPointCellParams("xy", 0, List.of())))
                .isEqualTo(new GetPointCellResult(
                        new PointCell(min, max, 2, true),
                        List.of(),
                        List.of(
                                new LeafPoint(0, false, new PointValue(List.of("80000001", "7fffffff"))),
                                new LeafPoint(1, false, new PointValue(List.of("80000002", "7ffffffe")))),
                        false));
    }

    @Test
    void checksTheFieldSegmentAndPath() throws Exception {
        currentIndex.replace(ReadOnlyIndex.open(writeIndex()));

        assertFails(
                () -> tree.handle(new GetPointTreeParams("nope")),
                ErrorCode.FIELD_NOT_FOUND,
                "No field 'nope' in the index");
        assertFails(
                () -> tree.handle(new GetPointTreeParams("id")),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: the field 'id' has no points");
        assertFails(
                () -> cell.handle(new GetPointCellParams("id", 0, List.of())),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: the field 'id' has no points");
        assertFails(
                () -> cell.handle(new GetPointCellParams("xy", 1, List.of())),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: No segment 1; the index has 1 segments");
        assertFails(
                () -> cell.handle(new GetPointCellParams("xy", 0, List.of(0))),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: No cell at [0] in segment 0");
    }

    @Test
    void needsAReader() throws Exception {
        currentIndex.replace(DirectoryOnlyIndex.open(writeIndex(), DirectoryImpl.FS));

        assertFails(() -> tree.handle(new GetPointTreeParams("xy")), ErrorCode.NO_READER, null);
        assertFails(() -> cell.handle(new GetPointCellParams("xy", 0, List.of())), ErrorCode.NO_READER, null);
    }

    @Test
    void failsWithoutOpenIndex() {
        assertFails(() -> tree.handle(new GetPointTreeParams("xy")), ErrorCode.NO_INDEX_OPEN, null);
    }

    /** One segment: "xy" is [1, -1] in document 0 and [2, -2] in document 1. */
    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (int i = 1; i <= 2; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.NO));
                doc.add(new IntPoint("xy", i, -i));
                writer.addDocument(doc);
            }
        }
        return dir;
    }

    private static void assertFails(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code, String message) {
        var thrown = assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(code));
        if (message != null) {
            thrown.hasMessage(message);
        }
    }
}
