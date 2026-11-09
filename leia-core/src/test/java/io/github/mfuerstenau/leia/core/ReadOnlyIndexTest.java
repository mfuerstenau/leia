package io.github.mfuerstenau.leia.core;

import static io.github.mfuerstenau.leia.core.TestIndexes.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import io.github.mfuerstenau.leia.core.IndexOpenException.Reason;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReadOnlyIndexTest {

    @TempDir
    Path tmp;

    @Test
    void opensLucene10Index() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 3, "2");

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(index.indexes())
                    .containsExactly(
                            new IndexSummary(dir, 0, "segments_2", 2, 3, Version.LATEST.major, Version.LATEST));
            assertThat(index.reader().numDocs()).isEqualTo(2);
        }
    }

    @Test
    void opensEmptyIndexWithoutSegments() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 0, null);

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(index.indexes().getFirst().numDocs()).isZero();
            assertThat(index.indexes().getFirst().minSegmentLuceneVersion()).isNull();
        }
    }

    @Test
    void opensLucene9Index() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(testIndex("lucene9"))) {
            IndexSummary summary = index.indexes().getFirst();
            assertThat(summary.createdVersionMajor()).isEqualTo(9);
            assertThat(summary.minSegmentLuceneVersion())
                    .satisfies(version -> assertThat(version.major).isEqualTo(9));
            assertThat(summary.numDocs()).isEqualTo(2);
            assertThat(summary.maxDoc()).isEqualTo(3);
            assertThat(((DirectoryReader) index.reader()).getIndexCommit().getUserData())
                    .containsEntry("writer", "leia LegacyIndexWriter");
        }
    }

    @Test
    void rejectsLucene8IndexAsUnsupported() {
        assertOpenFails(testIndex("lucene8"), Reason.UNSUPPORTED_FORMAT, "Unsupported index format");
    }

    @Test
    void opensIndexWithABundledCodec() throws Exception {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig().setCodec(Codec.forName("SimpleText")))) {
            Document doc = new Document();
            doc.add(new StringField("id", "1", Field.Store.YES));
            writer.addDocument(doc);
        }

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(index.reader().numDocs()).isEqualTo(1);
        }
    }

    @Test
    void rejectsIndexWithACodecThatIsNotLoaded() {
        assertOpenFails(
                testIndex("custom-codec"),
                Reason.MISSING_CODEC,
                "Missing codec: the index needs the codec 'LeiaTest', which isn't loaded");
    }

    @Test
    void rejectsIndexWithAFormatThatIsNotLoaded() {
        assertOpenFails(
                testIndex("custom-formats"),
                Reason.MISSING_CODEC,
                "Missing codec: the index needs the postings format 'LeiaTestPostings', which isn't loaded");
    }

    @Test
    void neverWritesToTheIndexDirectory() throws Exception {
        Path dir = copy(testIndex("lucene9"), tmp.resolve("index"));
        // The old IndexWriter left its lock file behind; without it, a lock taken on open would show.
        Files.delete(dir.resolve("write.lock"));
        Map<String, FileTime> before = snapshot(dir);

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(index.reader().maxDoc()).isEqualTo(3);
            assertThat(snapshot(dir)).isEqualTo(before);
        }
        assertThat(snapshot(dir)).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"FS, MMapDirectory", "MMAP, MMapDirectory", "NIO, NIOFSDirectory"})
    void opensThroughTheChosenDirectory(DirectoryImpl directoryImpl, String className) throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(testIndex("lucene9"), directoryImpl)) {
            assertThat(index.stats().directoryImpl()).isEqualTo(className);
            assertThat(index.document(0).docId()).isZero();
        }
    }

    @Test
    void neverWritesToTheIndexDirectoryThroughNio() throws Exception {
        Path dir = copy(testIndex("lucene9"), tmp.resolve("index"));
        Files.delete(dir.resolve("write.lock"));
        Map<String, FileTime> before = snapshot(dir);

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir, DirectoryImpl.NIO)) {
            assertThat(index.reader().maxDoc()).isEqualTo(3);
        }
        assertThat(snapshot(dir)).isEqualTo(before);
    }

    @Test
    void reopensThroughTheSameDirectory() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 1, null);
        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir, DirectoryImpl.NIO)) {
            addDocuments(dir, 2, 1);

            try (ReadOnlyIndex reopened = index.reopen().orElseThrow()) {
                assertThat(reopened.stats().directoryImpl()).isEqualTo("NIOFSDirectory");
            }
        }
    }

    @Test
    void closesTheReader() throws Exception {
        ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex(tmp.resolve("index"), 1, null));

        index.close();

        assertThat(index.reader().tryIncRef()).isFalse();
    }

    @Test
    void acceptsRelativePathAndNormalizesIt() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 1, null);
        Path relative = Path.of("").toAbsolutePath().relativize(dir.resolve("../index"));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(relative)) {
            assertThat(index.path()).isEqualTo(dir);
        }
    }

    @Test
    void rejectsMissingPath() {
        Path missing = tmp.resolve("missing");

        assertOpenFails(missing, Reason.NOT_FOUND, "No such file or directory: " + missing);
    }

    @Test
    void rejectsRegularFile() throws IOException {
        Path file = Files.writeString(tmp.resolve("file.txt"), "not an index");

        assertOpenFails(file, Reason.NOT_AN_INDEX, "Not a directory: " + file);
    }

    @Test
    void rejectsDirectoryWithoutIndex() throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("empty"));

        assertOpenFails(dir, Reason.NOT_AN_INDEX, "No Lucene index in or below " + dir);
    }

    @Test
    void rejectsIndexWithBrokenChecksum() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 3, null);
        Path segments = dir.resolve("segments_1");
        byte[] bytes = Files.readAllBytes(segments);
        bytes[bytes.length / 2] ^= 0x5A;
        Files.write(segments, bytes);

        assertOpenFails(dir, Reason.CORRUPT, "Corrupt index");
    }

    @Test
    void rejectsIndexWithMissingFile() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 3, null);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path si : files.filter(file -> file.toString().endsWith(".si")).toList()) {
                Files.delete(si);
            }
        }

        assertOpenFails(dir, Reason.CORRUPT, "Corrupt index, missing file");
    }

    @Test
    void rejectsUnreadableDirectory() throws Exception {
        assumeThat(FileSystems.getDefault().supportedFileAttributeViews()).contains("posix");
        Path dir = writeIndex(tmp.resolve("index"), 1, null);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("-wx------"));
        try {
            assumeThat(Files.isReadable(dir)).as("not running as root").isFalse();

            assertOpenFails(dir, Reason.NOT_READABLE, "Permission denied");
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void reopensNothingIfTheIndexIsUnchanged() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex(tmp.resolve("index"), 2, null))) {
            assertThat(index.reopen()).isEmpty();
            assertThat(index.reopen()).as("still owns its directory").isEmpty();
        }
    }

    @Test
    void reopensTheLatestCommitAndKeepsTheUnchangedSegments() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 2, null);
        ReadOnlyIndex index = ReadOnlyIndex.open(dir);
        addDocuments(dir, 3, 2);
        Map<String, FileTime> written = snapshot(dir);

        ReadOnlyIndex reopened = index.reopen().orElseThrow();
        try (reopened) {
            assertThat(reopened.indexes())
                    .containsExactly(
                            new IndexSummary(dir, 0, "segments_2", 4, 4, Version.LATEST.major, Version.LATEST));
            assertThat(reopened.reader().leaves()).hasSize(2);
            assertThat(reopened.reader()
                            .leaves()
                            .getFirst()
                            .reader()
                            .getCoreCacheHelper()
                            .getKey())
                    .isSameAs(index.reader()
                            .leaves()
                            .getFirst()
                            .reader()
                            .getCoreCacheHelper()
                            .getKey());
            assertThat(index.indexes().getFirst().maxDoc())
                    .as("the old index is unchanged")
                    .isEqualTo(2);
            assertThat(index.document(1).docId()).isEqualTo(1);

            index.close();

            assertThat(index.reader().tryIncRef()).isFalse();
            assertThat(reopened.stats().segmentCount())
                    .as("the directory is still open")
                    .isEqualTo(2);
            assertThat(reopened.document(3).docId()).isEqualTo(3);
            assertThat(snapshot(dir)).isEqualTo(written);
        }
    }

    @Test
    void reopensOnlyOnce() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 1, null);
        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            addDocuments(dir, 2, 1);
            index.reopen().orElseThrow().close();

            assertThatThrownBy(index::reopen)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Already reopened: " + dir);
        }
    }

    @Test
    void keepsTheIndexIfTheDirectoryIsGone() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 2, null);
        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            Path moved = Files.move(dir, tmp.resolve("moved"));

            assertReopenFails(index, Reason.NOT_FOUND, "No such directory: " + dir);
            Files.move(moved, dir);
            assertThat(index.reopen()).isEmpty();
        }
    }

    @Test
    void keepsTheIndexIfTheLatestCommitIsCorrupt() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 2, null);
        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            addDocuments(dir, 3, 1);
            Path segments = dir.resolve("segments_2");
            byte[] bytes = Files.readAllBytes(segments);
            bytes[bytes.length / 2] ^= 0x5A;
            Files.write(segments, bytes);

            assertReopenFails(index, Reason.CORRUPT, "Corrupt index");
            assertThat(index.document(1).docId()).isEqualTo(1);
            assertThat(index.stats().segmentCount()).isEqualTo(1);
        }
    }

    private static void assertReopenFails(ReadOnlyIndex index, Reason reason, String messagePrefix) {
        assertThatThrownBy(index::reopen)
                .isInstanceOfSatisfying(
                        IndexOpenException.class, e -> assertThat(e.reason()).isEqualTo(reason))
                .hasMessageStartingWith(messagePrefix);
    }

    private static void assertOpenFails(Path path, Reason reason, String messagePrefix) {
        assertThatThrownBy(() -> ReadOnlyIndex.open(path))
                .isInstanceOfSatisfying(
                        IndexOpenException.class, e -> assertThat(e.reason()).isEqualTo(reason))
                .hasMessageStartingWith(messagePrefix);
    }

    /** Writes and commits {@code docs} documents with ids 1.., then deletes {@code deleteId} if given. */
    private static Path writeIndex(Path dir, int docs, String deleteId) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(
                        // No merges, which would reclaim the deleted document.
                        directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            for (int i = 1; i <= docs; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
                writer.addDocument(doc);
            }
            writer.commit();
            // A second commit, so that the delete marks a document in a segment.
            if (deleteId != null) {
                writer.deleteDocuments(new Term("id", deleteId));
                writer.commit();
            }
        }
        return dir;
    }

    /** Adds and commits {@code docs} documents with ids from {@code firstId} on, in a segment of their own. */
    private static void addDocuments(Path dir, int firstId, int docs) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(
                        directory,
                        new IndexWriterConfig()
                                .setOpenMode(IndexWriterConfig.OpenMode.APPEND)
                                .setMergePolicy(NoMergePolicy.INSTANCE))) {
            for (int i = firstId; i < firstId + docs; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
                writer.addDocument(doc);
            }
            writer.commit();
        }
    }

    private static Path copy(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> files = Files.list(source)) {
            for (Path file : files.toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
        return target;
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
