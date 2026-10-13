package io.github.mfuerstenau.leia.server.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.core.DirectoryOnlyIndex;
import io.github.mfuerstenau.leia.core.IndexOpenException;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.IndexEntry;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReaderInfo;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexParams;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.job.Jobs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OpenIndexHandlerTest {

    @TempDir
    Path tmp;

    private final CurrentIndex currentIndex = new CurrentIndex(new Jobs(), new Filters());
    private final OpenIndexHandler handler = new OpenIndexHandler(currentIndex);

    @Test
    void opensIndexAndMakesItCurrent() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 2);

        OpenIndexResult result = handler.handle(new OpenIndexParams(dir.toString()));

        assertThat(result)
                .isEqualTo(new OpenIndexResult(
                        dir.toString(),
                        OpenMode.READER,
                        OptionalInt.of(2),
                        OptionalInt.of(2),
                        List.of(new IndexEntry(
                                dir.toString(),
                                Optional.of(new ReaderInfo(
                                        0,
                                        "segments_1",
                                        2,
                                        2,
                                        Version.LATEST.major,
                                        Optional.of(Version.LATEST.toString()))))),
                        List.of()));
        assertThat(currentIndex.get())
                .hasValueSatisfying(index -> assertThat(index.path()).isEqualTo(dir));
    }

    @ParameterizedTest
    @CsvSource({"FS, MMapDirectory", "MMAP, MMapDirectory", "NIO, NIOFSDirectory"})
    void opensThroughTheChosenDirectory(DirectoryImpl directoryImpl, String className) throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 1);

        handler.handle(new OpenIndexParams(dir.toString(), Optional.of(directoryImpl), Optional.empty()));

        assertThat(currentIndex.readable().stats().directoryImpl()).isEqualTo(className);
    }

    @Test
    void replacesAndClosesThePreviousIndex() throws Exception {
        handler.handle(new OpenIndexParams(writeIndex(tmp.resolve("first"), 1).toString()));
        ReadOnlyIndex first = currentIndex.readable();

        handler.handle(new OpenIndexParams(writeIndex(tmp.resolve("second"), 2).toString()));

        assertThat(currentIndex.readable().numDocs()).isEqualTo(2);
        assertThat(first.reader().tryIncRef()).as("first reader is closed").isFalse();
    }

    @Test
    void keepsThePreviousIndexOpenWhenOpeningFails() throws Exception {
        handler.handle(new OpenIndexParams(writeIndex(tmp.resolve("index"), 1).toString()));
        ReadOnlyIndex open = currentIndex.readable();

        assertThatThrownBy(() -> handler.handle(
                        new OpenIndexParams(tmp.resolve("missing").toString())))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INDEX_NOT_FOUND));

        assertThat(currentIndex.get()).containsSame(open);
        assertThat(open.reader().numDocs()).isEqualTo(1);
    }

    @Test
    void reportsNotAnIndex() throws IOException {
        Path empty = Files.createDirectory(tmp.resolve("empty"));

        assertThatThrownBy(() -> handler.handle(new OpenIndexParams(empty.toString())))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_AN_INDEX))
                .hasMessage("No Lucene index in or below " + empty);
    }

    @Test
    void opensTheIndexesBelowAPath() throws Exception {
        Path a = writeIndex(tmp.resolve("a"), 2);
        Path broken = writeIndex(tmp.resolve("b"), 1);
        try (var files = Files.list(broken)) {
            for (Path si : files.filter(file -> file.toString().endsWith(".si")).toList()) {
                Files.delete(si);
            }
        }
        Path c = writeIndex(tmp.resolve("c"), 3);

        OpenIndexResult result = handler.handle(new OpenIndexParams(tmp.toString()));

        assertThat(result.path()).isEqualTo(tmp.toString());
        assertThat(result.mode()).isEqualTo(OpenMode.READER);
        assertThat(result.numDocs()).hasValue(5);
        assertThat(result.maxDoc()).hasValue(5);
        assertThat(result.indexes())
                .extracting(
                        IndexEntry::path, entry -> entry.reader().orElseThrow().docBase())
                .containsExactly(tuple(a.toString(), 0), tuple(c.toString(), 2));
        assertThat(result.skipped()).singleElement().satisfies(skipped -> {
            assertThat(skipped.path()).isEqualTo(broken.toString());
            assertThat(skipped.message()).startsWith("Corrupt index, missing file");
        });
        assertThat(currentIndex.readable().maxDoc()).isEqualTo(5);
    }

    @Test
    void opensWithoutAReader() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 2);

        OpenIndexResult result = handler.handle(
                new OpenIndexParams(dir.toString(), Optional.empty(), Optional.of(OpenMode.DIRECTORY_ONLY)));

        assertThat(result)
                .isEqualTo(new OpenIndexResult(
                        dir.toString(),
                        OpenMode.DIRECTORY_ONLY,
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        List.of(new IndexEntry(dir.toString(), Optional.empty())),
                        List.of()));
        assertThat(currentIndex.opened()).isInstanceOf(DirectoryOnlyIndex.class);
        for (ThrowingCallable call : List.<ThrowingCallable>of(
                () -> new GetIndexStatsHandler(currentIndex).handle(new GetIndexStatsParams()),
                () -> new ReopenIndexHandler(currentIndex).handle(new ReopenIndexParams()),
                currentIndex::readable)) {
            assertThatThrownBy(call)
                    .isInstanceOfSatisfying(
                            RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NO_READER))
                    .hasMessage("Opened without a reader: only the commits can be read");
        }
    }

    @Test
    void opensACorruptIndexWithoutAReader() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 2);
        try (var files = Files.list(dir)) {
            for (Path si : files.filter(file -> file.toString().endsWith(".si")).toList()) {
                Files.delete(si);
            }
        }
        assertThatThrownBy(() -> handler.handle(new OpenIndexParams(dir.toString())))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CORRUPT_INDEX));

        OpenIndexResult result = handler.handle(
                new OpenIndexParams(dir.toString(), Optional.empty(), Optional.of(OpenMode.DIRECTORY_ONLY)));

        assertThat(result.indexes()).extracting(IndexEntry::path).containsExactly(dir.toString());
    }

    @Test
    void reportsInvalidPathAsInvalidParams() {
        assertThatThrownBy(() -> handler.handle(new OpenIndexParams("bad\0path")))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMS));
    }

    /** Each reason has an error code of its own. */
    @Test
    void everyReasonHasAnErrorCodeOfItsOwn() {
        assertThat(Arrays.stream(IndexOpenException.Reason.values()).map(OpenIndexHandler::errorCode))
                .doesNotHaveDuplicates()
                .hasSize(IndexOpenException.Reason.values().length);
    }

    private static Path writeIndex(Path dir, int docs) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (int i = 1; i <= docs; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
                writer.addDocument(doc);
            }
            writer.commit();
        }
        return dir;
    }
}
