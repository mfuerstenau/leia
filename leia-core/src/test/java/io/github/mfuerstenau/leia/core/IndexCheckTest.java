package io.github.mfuerstenau.leia.core;

import static io.github.mfuerstenau.leia.core.TestIndexes.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Checking an index directory with {@code CheckIndex} (ADR 0022). */
class IndexCheckTest {

    @TempDir
    Path tmp;

    @ParameterizedTest
    @EnumSource(DirectoryImpl.class)
    void findsNoProblemInAHealthyIndexAndWritesNothing(DirectoryImpl directoryImpl) throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 3);
        Map<String, FileTime> before = snapshot(dir);
        List<String> lines = new ArrayList<>();

        IndexCheck.Result result = IndexCheck.check(dir, directoryImpl, 2, lines::add, ScanProgress.NONE);

        assertThat(result.error()).isEmpty();
        assertThat(result.clean()).isTrue();
        assertThat(result.segmentsFile()).contains("segments_3");
        assertThat(result.segmentCount()).isEqualTo(3);
        assertThat(result.badSegments()).isZero();
        assertThat(result.validCounter()).isTrue();
        assertThat(result.segments()).extracting(IndexCheck.SegmentResult::name).containsExactly("_0", "_1", "_2");
        IndexCheck.SegmentResult first = result.segments().getFirst();
        assertThat(first.maxDoc()).isEqualTo(2);
        assertThat(first.codec()).isPresent();
        assertThat(first.openReaderPassed()).isTrue();
        assertThat(first.sizeBytes()).isPositive();
        assertThat(first.error()).isEmpty();
        assertThat(lines)
                .anyMatch(line -> line.startsWith("Segments file=segments_3 numSegments=3"))
                .anyMatch(line -> line.contains("test: terms, freq, prox...OK"))
                .contains("No problems were detected with this index.")
                .noneMatch(line -> line.contains("\n"));
        assertThat(snapshot(dir)).isEqualTo(before);
    }

    @Test
    void checksAnIndexWithOneSegmentInTheCallingThread() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 1);
        List<String> threads = new ArrayList<>();

        IndexCheck.Result result = IndexCheck.check(
                dir,
                DirectoryImpl.FS,
                1,
                _ -> threads.add(Thread.currentThread().getName()),
                ScanProgress.NONE);

        assertThat(result.clean()).isTrue();
        assertThat(threads).containsOnly(Thread.currentThread().getName());
    }

    @Test
    void listsTheSegmentsInCommitOrderWhenTheyGetSmaller() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 200, 20, 2);

        IndexCheck.Result result = IndexCheck.check(dir, DirectoryImpl.FS, 2, _ -> {}, ScanProgress.NONE);

        assertThat(result.clean()).isTrue();
        assertThat(result.segments()).extracting(IndexCheck.SegmentResult::name).containsExactly("_0", "_1", "_2");
        assertThat(result.segments())
                .extracting(IndexCheck.SegmentResult::maxDoc)
                .containsExactly(200, 20, 2);
    }

    @Test
    void reportsASegmentWithABadChecksum() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 2);
        Path cfs = dir.resolve("_1.cfs");
        byte[] bytes = Files.readAllBytes(cfs);
        bytes[bytes.length / 2] ^= 0x55;
        Files.write(cfs, bytes);

        IndexCheck.Result result = IndexCheck.check(dir, DirectoryImpl.FS, 1, _ -> {}, ScanProgress.NONE);

        assertThat(result.error()).isEmpty();
        assertThat(result.clean()).isFalse();
        assertThat(result.badSegments()).isEqualTo(1);
        assertThat(result.lostDocs()).isEqualTo(2);
        assertThat(result.segments().get(0).error()).isEmpty();
        assertThat(result.segments().get(1).error())
                .hasValueSatisfying(error -> assertThat(error).containsIgnoringCase("checksum"));
    }

    @Test
    void reportsACommitThatCantBeRead() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 1);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path si : files.filter(file -> file.toString().endsWith(".si")).toList()) {
                Files.delete(si);
            }
        }
        List<String> lines = new ArrayList<>();

        IndexCheck.Result result = IndexCheck.check(dir, DirectoryImpl.FS, 1, lines::add, ScanProgress.NONE);

        assertThat(result.error()).isEmpty();
        assertThat(result.clean()).isFalse();
        assertThat(result.missingSegments()).isTrue();
        assertThat(lines).anyMatch(line -> line.startsWith("ERROR: could not read latest commit point"));
    }

    @Test
    void checksALucene8IndexThatAReaderCantOpen() {
        assertThatThrownBy(() -> ReadOnlyIndex.open(testIndex("lucene8"))).isInstanceOf(IndexOpenException.class);

        IndexCheck.Result result =
                IndexCheck.check(testIndex("lucene8"), DirectoryImpl.FS, 1, _ -> {}, ScanProgress.NONE);

        assertThat(result.error()).isEmpty();
        assertThat(result.clean()).isTrue();
        assertThat(result.segments()).isNotEmpty();
    }

    @Test
    void checksALucene9Index() {
        IndexCheck.Result result =
                IndexCheck.check(testIndex("lucene9"), DirectoryImpl.FS, 2, _ -> {}, ScanProgress.NONE);

        assertThat(result.clean()).isTrue();
        assertThat(result.segments()).isNotEmpty();
    }

    @Test
    void aDirectoryWithoutACommitCantBeChecked() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("empty"));

        IndexCheck.Result result = IndexCheck.check(dir, DirectoryImpl.FS, 1, _ -> {}, ScanProgress.NONE);

        assertThat(result.clean()).isFalse();
        assertThat(result.error()).hasValueSatisfying(error -> assertThat(error).contains("no segments* file"));
        assertThat(result.segments()).isEmpty();
    }

    @Test
    void rejectsALevelOutOfRange() {
        assertThatThrownBy(() -> IndexCheck.check(tmp, DirectoryImpl.FS, 4, _ -> {}, ScanProgress.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(DirectoryImpl.class)
    void stopsOnceCancelled(DirectoryImpl directoryImpl) throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 3);
        AtomicInteger calls = new AtomicInteger();
        ScanProgress cancelled = _ -> {
            if (calls.incrementAndGet() > 2) {
                throw new CancellationException("cancelled");
            }
        };

        assertThatThrownBy(() -> IndexCheck.check(dir, directoryImpl, 3, _ -> {}, cancelled))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void theCheckDirectoryRefusesToWrite() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 1);
        try (Directory directory = new CheckDirectory(FSDirectory.open(dir), cancellation(new AtomicInteger()))) {
            assertThatThrownBy(() -> directory.createOutput("x", IOContext.DEFAULT))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("Read-only");
            assertThatThrownBy(() -> directory.deleteFile("segments_1"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> directory.rename("segments_1", "x"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> directory.copyFrom(directory, "segments_1", "y", IOContext.DEFAULT))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(dir.resolve("x")).doesNotExist();
        assertThat(dir.resolve("segments_1")).exists();
    }

    @Test
    void theCheckDirectoryAsksAboutCancellationEvery1024PositioningReads() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 1);
        AtomicInteger checks = new AtomicInteger();
        try (Directory directory = new CheckDirectory(FSDirectory.open(dir), cancellation(checks));
                IndexInput input = directory.openInput("segments_1", IOContext.DEFAULT)) {
            assertThat(checks).hasValue(1);
            for (int i = 0; i < CheckDirectory.CHECK_INTERVAL * 2; i++) {
                input.seek(0);
                input.readByte();
            }
            assertThat(checks).hasValue(3);
        }
    }

    /** Counts the checks, and never stops. */
    private static CheckDirectory.Cancellation cancellation(AtomicInteger checks) {
        return new CheckDirectory.Cancellation() {
            @Override
            public boolean stopped() {
                return false;
            }

            @Override
            public void check() {
                checks.incrementAndGet();
            }
        };
    }

    /** Writes {@code segments} commits of two documents each, one segment per commit. */
    private static Path writeIndex(Path dir, int segments) throws IOException {
        int[] docs = new int[segments];
        Arrays.fill(docs, 2);
        return writeIndex(dir, docs);
    }

    /** Writes a commit for each of {@code docs}, one segment per commit, with that many documents. */
    private static Path writeIndex(Path dir, int... docs) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            for (int segment = 0; segment < docs.length; segment++) {
                for (int i = 0; i < docs[segment]; i++) {
                    Document doc = new Document();
                    doc.add(new StringField("id", segment + "-" + i, Field.Store.YES));
                    doc.add(new TextField("body", "some text for segment " + segment, Field.Store.NO));
                    writer.addDocument(doc);
                }
                writer.commit();
            }
        }
        return dir;
    }

    /** File name → last modified time of every file in {@code dir}. */
    private static Map<String, FileTime> snapshot(Path dir) throws IOException {
        Map<String, FileTime> files = new TreeMap<>();
        try (Stream<Path> list = Files.list(dir)) {
            for (Path file : list.toList()) {
                files.put(file.getFileName().toString(), Files.getLastModifiedTime(file));
            }
        }
        return files;
    }
}
