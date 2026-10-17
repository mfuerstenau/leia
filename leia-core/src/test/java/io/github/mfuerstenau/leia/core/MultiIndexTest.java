package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.core.IndexDocument.StoredValue;
import io.github.mfuerstenau.leia.core.IndexOpenException.Reason;
import io.github.mfuerstenau.leia.core.SearchResults.Hit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opening a path with several indexes below it, and opening without a reader (ADR 0021). */
class MultiIndexTest {

    @TempDir
    Path tmp;

    @Test
    void opensTheIndexesBelowAPathAsOne() throws Exception {
        Path a = writeIndex(tmp.resolve("a"), "a", 2);
        Path b = writeIndex(tmp.resolve("b/nested"), "b", 3);
        Files.createDirectories(tmp.resolve("c/empty"));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(tmp)) {
            assertThat(index.path()).isEqualTo(tmp);
            assertThat(index.directories()).containsExactly(a, b);
            assertThat(index.indexes())
                    .extracting(IndexSummary::path, IndexSummary::docBase, IndexSummary::maxDoc)
                    .containsExactly(tuple(a, 0, 2), tuple(b, 2, 3));
            assertThat(index.skipped()).isEmpty();
            assertThat(index.maxDoc()).isEqualTo(5);
            assertThat(index.numDocs()).isEqualTo(5);

            assertThat(index.storedValues(0, "id")).containsExactly(new StoredValue.Text("a1"));
            assertThat(index.storedValues(2, "id")).containsExactly(new StoredValue.Text("b1"));
            assertThat(index.terms("index", Optional.empty(), 10).terms())
                    .extracting(entry -> entry.term().utf8ToString(), TermPage.Entry::docFreq)
                    .containsExactly(tuple("a", 2), tuple("b", 3));
            assertThat(search(index, "index", "b")).containsExactly(2, 3, 4);
            DocFilter filter = index.matches(new TermQuery(new Term("index", "b")), false, _ -> {});
            assertThat(filter.matchCount()).isEqualTo(3);
            assertThat(filter.step(0, true)).contains(new DocFilter.Match(2, 0));
            assertThat(filter.step(5, false)).contains(new DocFilter.Match(4, 2));

            IndexStats stats = index.stats();
            assertThat(stats.maxDoc()).isEqualTo(5);
            assertThat(stats.segmentCount()).isEqualTo(2);
            assertThat(stats.indexes())
                    .extracting(commit -> commit.summary().path(), IndexStats.CommitStats::generation)
                    .containsExactly(tuple(a, 1L), tuple(b, 1L));
            // The second directory's segments start at its docBase.
            assertThat(stats.indexes().get(1).segments())
                    .singleElement()
                    .isEqualTo(new IndexStats.SegmentStats("_0", 2, 3, 3));
            assertThat(stats.field("id").orElseThrow().terms().orElseThrow().docCount())
                    .isEqualTo(5);
        }
    }

    @Test
    void readsTheCommitsOfEachIndex() throws Exception {
        writeIndex(tmp.resolve("a"), "a", 1);
        Path b = writeIndex(tmp.resolve("b"), "b", 1);
        addDocuments(b, "b", 2, 1);

        try (ReadOnlyIndex index = ReadOnlyIndex.open(tmp)) {
            assertThat(index.commits(0).commits())
                    .extracting(CommitInfo::generation)
                    .containsExactly(1L);
            assertThat(index.commits(1).commits())
                    .extracting(CommitInfo::generation, CommitInfo::current)
                    .containsExactly(tuple(2L, true));
            assertThat(index.commit(1, 2)).isPresent();
            assertThat(index.commit(0, 2)).isEmpty();
            assertThatThrownBy(() -> index.commits(2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No index 2; there are 2");
        }
    }

    @Test
    void aPathThatHoldsAnIndexOpensOnlyThatIndex() throws Exception {
        Path outer = writeIndex(tmp.resolve("outer"), "outer", 1);
        writeIndex(outer.resolve("inner"), "inner", 1);

        try (ReadOnlyIndex index = ReadOnlyIndex.open(outer)) {
            assertThat(index.directories()).containsExactly(outer);
        }
        try (ReadOnlyIndex index = ReadOnlyIndex.open(tmp)) {
            assertThat(index.directories()).as("nothing below an index").containsExactly(outer);
        }
    }

    @Test
    void walksDepthFirstInNameOrder() throws Exception {
        Path deep = writeIndex(tmp.resolve("a/z"), "1", 1);
        Path shallow = writeIndex(tmp.resolve("b"), "2", 1);
        Path deeper = writeIndex(tmp.resolve("a/b/c"), "3", 1);

        try (ReadOnlyIndex index = ReadOnlyIndex.open(tmp)) {
            assertThat(index.directories()).containsExactly(deeper, deep, shallow);
        }
    }

    @Test
    void doesNotFollowSymbolicLinks() throws Exception {
        Path real = writeIndex(tmp.resolve("indexes/real"), "real", 1);
        Path links = Files.createDirectory(tmp.resolve("links"));
        Files.createSymbolicLink(links.resolve("link"), real);

        assertThatThrownBy(() -> ReadOnlyIndex.open(links))
                .isInstanceOfSatisfying(
                        IndexOpenException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_AN_INDEX))
                .hasMessage("No Lucene index in or below " + links);
    }

    @Test
    void stopsWalkingAfterTooManyDirectories() throws Exception {
        for (int i = 0; i < 5; i++) {
            Files.createDirectories(tmp.resolve("d" + i));
        }

        assertThatThrownBy(() -> IndexFinder.find(tmp, 3))
                .isInstanceOfSatisfying(
                        IndexOpenException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_AN_INDEX))
                .hasMessage("More than 3 directories below " + tmp + "; open a path closer to the indexes");
    }

    @Test
    void skipsAnIndexThatFailsToOpenAndReportsIt() throws Exception {
        Path a = writeIndex(tmp.resolve("a"), "a", 2);
        Path broken = corrupt(writeIndex(tmp.resolve("b"), "b", 1));
        Path c = writeIndex(tmp.resolve("c"), "c", 1);

        try (ReadOnlyIndex index = ReadOnlyIndex.open(tmp)) {
            assertThat(index.directories()).containsExactly(a, c);
            assertThat(index.indexes()).extracting(IndexSummary::docBase).containsExactly(0, 2);
            assertThat(index.skipped()).singleElement().satisfies(skipped -> {
                assertThat(skipped.path()).isEqualTo(broken);
                assertThat(skipped.message()).startsWith("Corrupt index, missing file");
            });
        }
    }

    @Test
    void failsWithTheFirstErrorIfNoIndexOpens() throws Exception {
        Path first = corrupt(writeIndex(tmp.resolve("a"), "a", 1));
        corrupt(writeIndex(tmp.resolve("b"), "b", 1));

        assertThatThrownBy(() -> ReadOnlyIndex.open(tmp))
                .isInstanceOfSatisfying(
                        IndexOpenException.class, e -> assertThat(e.reason()).isEqualTo(Reason.CORRUPT))
                .hasMessageStartingWith(first + ": Corrupt index, missing file");
    }

    @Test
    void namesTheOnlyIndexBelowAPathIfItFailsToOpen() throws Exception {
        Path broken = corrupt(writeIndex(tmp.resolve("nested/index"), "a", 1));

        assertThatThrownBy(() -> ReadOnlyIndex.open(tmp))
                .isInstanceOfSatisfying(
                        IndexOpenException.class, e -> assertThat(e.reason()).isEqualTo(Reason.CORRUPT))
                .hasMessageStartingWith(broken + ": Corrupt index, missing file");
    }

    @Test
    void skipsAnIndexWhoseFieldsConflict() throws Exception {
        Path a = writeIndex(tmp.resolve("a"), "a", 1, doc -> doc.add(new NumericDocValuesField("rank", 1)));
        Path b = writeIndex(
                tmp.resolve("b"), "b", 1, doc -> doc.add(new SortedDocValuesField("rank", new BytesRef("x"))));
        Path c = writeIndex(tmp.resolve("c"), "c", 1);

        try (ReadOnlyIndex index = ReadOnlyIndex.open(tmp)) {
            assertThat(index.directories()).containsExactly(a, c);
            assertThat(index.skipped()).singleElement().satisfies(skipped -> {
                assertThat(skipped.path()).isEqualTo(b);
                assertThat(skipped.message())
                        .startsWith("Its fields conflict with those of the indexes before it: ")
                        .contains("rank");
            });
            assertThat(index.stats().field("rank")).isPresent();
        }
    }

    @Test
    void reopensTheIndexesThatChangedAndSharesTheOthers() throws Exception {
        writeIndex(tmp.resolve("a"), "a", 2);
        Path b = writeIndex(tmp.resolve("b"), "b", 1);
        ReadOnlyIndex index = ReadOnlyIndex.open(tmp);
        addDocuments(b, "b", 2, 2);
        Map<String, FileTime> written = snapshot(b);

        ReadOnlyIndex reopened = index.reopen().orElseThrow();
        try (reopened) {
            assertThat(reopened.indexes())
                    .extracting(IndexSummary::segmentsFile, IndexSummary::docBase, IndexSummary::maxDoc)
                    .containsExactly(tuple("segments_1", 0, 2), tuple("segments_2", 2, 3));
            assertThat(reopened.maxDoc()).isEqualTo(5);
            assertThat(index.maxDoc()).as("the old index is unchanged").isEqualTo(3);

            index.close();

            assertThat(index.reader().tryIncRef())
                    .as("the old reader is closed")
                    .isFalse();
            assertThat(reopened.storedValues(0, "id"))
                    .as("the shared reader is still open")
                    .containsExactly(new StoredValue.Text("a1"));
            assertThat(reopened.storedValues(4, "id")).containsExactly(new StoredValue.Text("b3"));
            assertThat(search(reopened, "index", "a")).containsExactly(0, 1);
            assertThat(reopened.commits(1).commits())
                    .extracting(CommitInfo::generation, CommitInfo::current)
                    .containsExactly(tuple(2L, true));
            assertThat(snapshot(b)).isEqualTo(written);
        }
        assertThat(reopened.reader().tryIncRef()).isFalse();
    }

    @Test
    void reopensNothingIfNoIndexChanged() throws Exception {
        writeIndex(tmp.resolve("a"), "a", 1);
        writeIndex(tmp.resolve("b"), "b", 1);

        try (ReadOnlyIndex index = ReadOnlyIndex.open(tmp)) {
            assertThat(index.reopen()).isEmpty();
        }
    }

    @Test
    void aFailedReopenNamesTheIndexAndKeepsTheOldOne() throws Exception {
        writeIndex(tmp.resolve("a"), "a", 1);
        Path b = writeIndex(tmp.resolve("b"), "b", 1);
        try (ReadOnlyIndex index = ReadOnlyIndex.open(tmp)) {
            addDocuments(b, "b", 2, 1);
            Path segments = b.resolve("segments_2");
            byte[] bytes = Files.readAllBytes(segments);
            bytes[bytes.length / 2] ^= 0x5A;
            Files.write(segments, bytes);

            assertThatThrownBy(index::reopen)
                    .isInstanceOfSatisfying(
                            IndexOpenException.class,
                            e -> assertThat(e.reason()).isEqualTo(Reason.CORRUPT))
                    .hasMessageStartingWith(b + ": Corrupt index");
            assertThat(index.storedValues(1, "id")).containsExactly(new StoredValue.Text("b1"));
        }
    }

    @Test
    void opensWithoutAReader() throws Exception {
        Path dir = corrupt(writeIndex(tmp.resolve("index"), "x", 2));
        Map<String, FileTime> written = snapshot(dir);

        try (DirectoryOnlyIndex index = DirectoryOnlyIndex.open(dir, DirectoryImpl.NIO)) {
            assertThat(index.path()).isEqualTo(dir);
            assertThat(index.directories()).containsExactly(dir);
            CommitList commits = index.commits(0);
            assertThat(commits.commits()).isEmpty();
            assertThat(commits.unreadable()).singleElement().satisfies(unreadable -> {
                assertThat(unreadable.segmentsFile()).isEqualTo("segments_1");
                assertThat(unreadable.message()).startsWith("Corrupt index, missing file: ");
            });
        }
        assertThat(snapshot(dir)).isEqualTo(written);
    }

    @Test
    void opensTheIndexesBelowAPathWithoutAReader() throws Exception {
        Path a = writeIndex(tmp.resolve("a"), "a", 1);
        Path b = corrupt(writeIndex(tmp.resolve("b"), "b", 1));

        try (DirectoryOnlyIndex index = DirectoryOnlyIndex.open(tmp, DirectoryImpl.FS)) {
            assertThat(index.directories()).containsExactly(a, b);
            assertThat(index.commits(0).commits())
                    .extracting(CommitInfo::generation, CommitInfo::current)
                    .as("no commit is the opened one")
                    .containsExactly(tuple(1L, false));
            assertThat(index.commit(0, 1)).isPresent();
            assertThat(index.commits(1).unreadable()).hasSize(1);
            assertThatThrownBy(() -> index.commit(2, 1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void withoutAReaderStillNeedsAnIndex() throws Exception {
        Path empty = Files.createDirectory(tmp.resolve("empty"));

        assertThatThrownBy(() -> DirectoryOnlyIndex.open(empty, DirectoryImpl.FS))
                .isInstanceOfSatisfying(
                        IndexOpenException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_AN_INDEX))
                .hasMessage("No Lucene index in or below " + empty);
        assertThatThrownBy(() -> DirectoryOnlyIndex.open(tmp.resolve("missing"), DirectoryImpl.FS))
                .isInstanceOfSatisfying(
                        IndexOpenException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_FOUND));
    }

    private static List<Integer> search(ReadOnlyIndex index, String field, String term) throws Exception {
        return index
                .search(
                        new TermQuery(new Term(field, term)),
                        new BM25Similarity(),
                        Optional.empty(),
                        0,
                        10,
                        true,
                        Optional.empty(),
                        _ -> {})
                .hits()
                .stream()
                .map(Hit::docId)
                .sorted()
                .toList();
    }

    private interface DocumentExtras {
        void add(Document doc);
    }

    private static Path writeIndex(Path dir, String name, int docs) throws IOException {
        return writeIndex(dir, name, docs, _ -> {});
    }

    /**
     * Writes and commits {@code docs} documents with the ids {@code name1}, {@code name2}, … and
     * {@code name} in the field "index".
     */
    private static Path writeIndex(Path dir, String name, int docs, DocumentExtras extras) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (int i = 1; i <= docs; i++) {
                writer.addDocument(document(name, i, extras));
            }
            writer.commit();
        }
        return dir;
    }

    /** Adds and commits documents with ids from {@code name + firstId} on, in a segment of their own. */
    private static void addDocuments(Path dir, String name, int firstId, int docs) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(
                        directory,
                        new IndexWriterConfig()
                                .setOpenMode(IndexWriterConfig.OpenMode.APPEND)
                                .setMergePolicy(NoMergePolicy.INSTANCE))) {
            for (int i = firstId; i < firstId + docs; i++) {
                writer.addDocument(document(name, i, _ -> {}));
            }
            writer.commit();
        }
    }

    private static Document document(String name, int i, DocumentExtras extras) {
        Document doc = new Document();
        doc.add(new StringField("id", name + i, Field.Store.YES));
        doc.add(new StringField("index", name, Field.Store.NO));
        extras.add(doc);
        return doc;
    }

    /** Deletes the {@code .si} files, so that a reader can't open the index. */
    private static Path corrupt(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path si : files.filter(file -> file.toString().endsWith(".si")).toList()) {
                Files.delete(si);
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
