package io.github.mfuerstenau.leia.server.commits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.core.DirectoryImpl;
import io.github.mfuerstenau.leia.core.DirectoryOnlyIndex;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.commits.Commit;
import io.github.mfuerstenau.leia.protocol.commits.CommitFile;
import io.github.mfuerstenau.leia.protocol.commits.FieldFormats;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsResult;
import io.github.mfuerstenau.leia.protocol.commits.Segment;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.IndexDirectories;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoDeletionPolicy;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code getCommits} and {@code getCommit}. */
class CommitHandlersTest {

    @TempDir
    Path tmp;

    private final CurrentIndex currentIndex = new CurrentIndex(new Jobs(), new Filters());
    private final IndexDirectories directories = new IndexDirectories(currentIndex, new Runtimes());
    private final GetCommitsHandler getCommits = new GetCommitsHandler(currentIndex, directories);
    private final GetCommitHandler getCommit = new GetCommitHandler(currentIndex, directories);

    @Test
    void listsTheCommitsNewestFirst() throws Exception {
        openIndex();

        assertThat(getCommits.handle(new GetCommitsParams()).commits())
                .containsExactly(
                        new Commit(2, "segments_2", true, 9, 2, 3, 1, Version.LATEST.toString(), Map.of("step", "two")),
                        new Commit(1, "segments_1", false, 4, 1, 2, 0, Version.LATEST.toString(), Map.of()));
    }

    @Test
    void readsACommitWhole() throws Exception {
        openIndex();

        GetCommitResult result = getCommit.handle(new GetCommitParams(2));

        assertThat(result.commit().segmentsFile()).isEqualTo("segments_2");
        assertThat(result.segments()).extracting(Segment::name).containsExactly("_0", "_1");
        Segment first = result.segments().getFirst();
        assertThat(first.docBase()).isZero();
        assertThat(first.delCount()).isEqualTo(1);
        assertThat(first.delGen()).hasValue(1);
        assertThat(first.luceneVersion()).isEqualTo(Version.LATEST.toString());
        assertThat(first.formats().orElseThrow().postings()).isEqualTo("PerField40");
        assertThat(first.diagnostics()).containsEntry("source", "flush");
        assertThat(first.fieldFormats())
                .containsExactly(new FieldFormats("id", Optional.of("Lucene104"), Optional.empty(), Optional.empty()));
        assertThat(first.fieldInfosError()).isEmpty();
        assertThat(result.segments().get(1).docBase()).isEqualTo(2);
        assertThat(result.files())
                .filteredOn(file -> file.name().equals("segments_2"))
                .singleElement()
                .extracting(CommitFile::segment)
                .isEqualTo(Optional.empty());
    }

    @Test
    void fieldInfosThatCantBeReadAreAnErrorOfTheirSegment() throws Exception {
        Path dir = openIndex();
        Files.delete(dir.resolve("_1.cfs"));

        GetCommitResult result = getCommit.handle(new GetCommitParams(2));

        assertThat(result.segments().getFirst().fieldFormats()).hasSize(1);
        Segment broken = result.segments().get(1);
        assertThat(broken.fieldFormats()).isEmpty();
        assertThat(broken.fieldInfosError())
                .hasValueSatisfying(message -> assertThat(message)
                        .startsWith("Corrupt index, missing file: ")
                        .contains("_1.cfs"));
    }

    @Test
    void anUnknownGenerationIsCommitNotFound() throws Exception {
        openIndex();

        assertFails(() -> getCommit.handle(new GetCommitParams(7)), ErrorCode.COMMIT_NOT_FOUND);
    }

    @Test
    void anUnreadableCommitIsSkippedInTheListAndFailsWhenRead() throws Exception {
        Path dir = openIndex();
        // Not a segments file at all: Lucene takes it for one of a format too old to read.
        Files.writeString(dir.resolve("segments_8"), "not a commit");
        byte[] commit = Files.readAllBytes(dir.resolve("segments_1"));
        Files.write(dir.resolve("segments_9"), Arrays.copyOf(commit, commit.length / 2));

        assertThat(getCommits.handle(new GetCommitsParams()).commits())
                .extracting(Commit::generation)
                .containsExactly(2L, 1L);
        assertFails(() -> getCommit.handle(new GetCommitParams(8)), ErrorCode.UNSUPPORTED_INDEX_FORMAT);
        assertFails(() -> getCommit.handle(new GetCommitParams(9)), ErrorCode.CORRUPT_INDEX);
    }

    @Test
    void readsTheCommitsOfTheChosenIndex() throws Exception {
        Path dir = openIndex();
        Path second = Files.createDirectories(tmp.resolve("second"));
        try (FSDirectory directory = FSDirectory.open(second);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.addDocument(doc("9"));
            writer.commit();
        }
        currentIndex.replace(ReadOnlyIndex.open(tmp));

        assertThat(getCommits.handle(new GetCommitsParams(OptionalInt.of(0))).commits())
                .extracting(Commit::generation)
                .containsExactly(2L, 1L);
        assertThat(getCommits.handle(new GetCommitsParams(OptionalInt.of(1))).commits())
                .extracting(Commit::generation)
                .containsExactly(1L);
        assertThat(getCommit
                        .handle(new GetCommitParams(1, OptionalInt.of(1)))
                        .commit()
                        .maxDoc())
                .isEqualTo(1);
        assertThat(currentIndex.opened().directories()).containsExactly(dir, second);
        assertThatThrownBy(() -> getCommits.handle(new GetCommitsParams(OptionalInt.of(2))))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMS))
                .hasMessage("Invalid params: no index 2; there are 2");
        assertFails(() -> getCommit.handle(new GetCommitParams(1, OptionalInt.of(-1))), ErrorCode.INVALID_PARAMS);
    }

    @Test
    void readsTheCommitsWithoutAReader() throws Exception {
        Path dir = openIndex();
        currentIndex.replace(DirectoryOnlyIndex.open(dir, DirectoryImpl.FS));

        assertThat(getCommits.handle(new GetCommitsParams()).commits())
                .extracting(Commit::generation, Commit::current)
                .containsExactly(tuple(2L, false), tuple(1L, false));
        assertThat(getCommit.handle(new GetCommitParams(2)).segments()).hasSize(2);
    }

    @Test
    void listsTheCommitsThatCantBeRead() throws Exception {
        Path dir = openIndex();
        try (var files = Files.list(dir)) {
            for (Path si :
                    files.filter(file -> file.toString().endsWith("_1.si")).toList()) {
                Files.delete(si);
            }
        }
        currentIndex.replace(DirectoryOnlyIndex.open(dir, DirectoryImpl.FS));

        GetCommitsResult result = getCommits.handle(new GetCommitsParams());

        assertThat(result.commits()).extracting(Commit::generation).containsExactly(1L);
        assertThat(result.unreadable()).singleElement().satisfies(unreadable -> {
            assertThat(unreadable.segmentsFile()).isEqualTo("segments_2");
            assertThat(unreadable.message()).startsWith("Corrupt index, missing file: ");
        });
    }

    @Test
    void needsAnOpenIndex() {
        assertFails(() -> getCommits.handle(new GetCommitsParams()), ErrorCode.NO_INDEX_OPEN);
        assertFails(() -> getCommit.handle(new GetCommitParams(1)), ErrorCode.NO_INDEX_OPEN);
    }

    private static void assertFails(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    /** Two commits, both kept; the second adds a segment, deletes a document and has user data. */
    private Path openIndex() throws Exception {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(
                        directory,
                        new IndexWriterConfig()
                                .setMergePolicy(NoMergePolicy.INSTANCE)
                                .setIndexDeletionPolicy(NoDeletionPolicy.INSTANCE))) {
            writer.addDocument(doc("1"));
            writer.addDocument(doc("2"));
            writer.commit();
            writer.addDocument(doc("3"));
            writer.deleteDocuments(new Term("id", "1"));
            writer.setLiveCommitData(Map.of("step", "two").entrySet());
            writer.commit();
        }
        currentIndex.replace(ReadOnlyIndex.open(dir));
        return dir;
    }

    private static Document doc(String id) {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.YES));
        return doc;
    }
}
