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
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadOnlyIndexTest {

    @TempDir
    Path tmp;

    @Test
    void opensLucene10Index() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 3, "2");

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(index.summary())
                    .isEqualTo(new IndexSummary(
                            dir, "segments_2", 2, 3, Version.LATEST.major, Optional.of(Version.LATEST)));
            assertThat(index.reader().numDocs()).isEqualTo(2);
        }
    }

    @Test
    void opensEmptyIndexWithoutSegments() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), 0, null);

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(index.summary().numDocs()).isZero();
            assertThat(index.summary().minSegmentLuceneVersion()).isEmpty();
        }
    }

    @Test
    void opensLucene9Index() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(testIndex("lucene9"))) {
            IndexSummary summary = index.summary();
            assertThat(summary.createdVersionMajor()).isEqualTo(9);
            assertThat(summary.minSegmentLuceneVersion())
                    .hasValueSatisfying(version -> assertThat(version.major).isEqualTo(9));
            assertThat(summary.numDocs()).isEqualTo(2);
            assertThat(summary.maxDoc()).isEqualTo(3);
            assertThat(index.reader().getIndexCommit().getUserData()).containsEntry("writer", "leia LegacyIndexWriter");
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
            assertThat(index.summary().path()).isEqualTo(dir);
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

        assertOpenFails(dir, Reason.NOT_AN_INDEX, "Not a Lucene index (no segments_N file): " + dir);
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
