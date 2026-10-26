package io.github.mfuerstenau.leia.server.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.check.CheckTargetIndex;
import io.github.mfuerstenau.leia.protocol.check.CheckTargetSegment;
import io.github.mfuerstenau.leia.protocol.check.GetCheckTargetsParams;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.TestIndexes;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.IndexDirectories;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code getCheckTargets}: the indexes and segments that {@code startCheckIndex} can be told to check (ADR 0093). */
class GetCheckTargetsHandlerTest {

    @TempDir
    Path tmp;

    private final CurrentIndex currentIndex = new CurrentIndex(new Jobs(), new Filters());
    private final GetCheckTargetsHandler handler =
            new GetCheckTargetsHandler(new IndexDirectories(currentIndex, new Runtimes()));

    private List<CheckTargetIndex> targets() throws RpcException {
        return handler.handle(new GetCheckTargetsParams()).indexes();
    }

    @Test
    void listsTheSegmentsOfTheLatestCommit() throws Exception {
        Path dir = writeSegments(tmp.resolve("index"), 3);
        currentIndex.replace(TestIndexes.open(dir));

        assertThat(targets()).singleElement().satisfies(index -> {
            assertThat(index.path()).isEqualTo(dir.toString());
            assertThat(index.skipped()).isFalse();
            assertThat(index.error()).isEmpty();
            assertThat(index.segmentsFile()).contains("segments_3");
            assertThat(index.segments())
                    .extracting(CheckTargetSegment::name, CheckTargetSegment::maxDoc)
                    .containsExactly(tuple("_0", 2), tuple("_1", 2), tuple("_2", 2));
            assertThat(index.segments())
                    .allSatisfy(
                            segment -> assertThat(segment.sizeBytes().orElse(0)).isPositive());
        });
    }

    @Test
    void listsTheSkippedIndexesAfterTheOpenedOnesWithWhyTheyCantBeListed() throws Exception {
        Path a = writeSegments(tmp.resolve("indexes/a"), 1);
        Path b = corrupt(writeSegments(tmp.resolve("indexes/b"), 2));
        currentIndex.replace(TestIndexes.open(tmp.resolve("indexes")));

        List<CheckTargetIndex> indexes = targets();

        assertThat(indexes)
                .extracting(
                        CheckTargetIndex::path,
                        CheckTargetIndex::skipped,
                        index -> index.segments().size())
                .containsExactly(tuple(a.toString(), false, 1), tuple(b.toString(), true, 0));
        // The check would see the same commit, and report it as broken.
        assertThat(indexes.get(1).error())
                .hasValueSatisfying(error -> assertThat(error).contains("can't be read", "_0.si"));
    }

    @Test
    void worksWithoutAReader() throws Exception {
        Path dir = writeSegments(tmp.resolve("index"), 2);
        currentIndex.replace(TestIndexes.withoutReader(dir, DirectoryKind.NIO));

        assertThat(targets()).singleElement().satisfies(index -> {
            assertThat(index.error()).isEmpty();
            assertThat(index.skipped()).isFalse();
            assertThat(index.segments()).extracting(CheckTargetSegment::name).containsExactly("_0", "_1");
        });
    }

    @Test
    void anIndexWithoutACommitHasAnErrorAndDoesNotFailTheCall() throws Exception {
        Path a = writeSegments(tmp.resolve("indexes/a"), 1);
        Path b = writeSegments(tmp.resolve("indexes/b"), 1);
        currentIndex.replace(TestIndexes.open(tmp.resolve("indexes")));
        deleteSegmentsFiles(b);

        assertThat(targets())
                .extracting(CheckTargetIndex::path, index -> index.error().isPresent(), CheckTargetIndex::segments)
                .containsExactly(
                        tuple(a.toString(), false, targets().getFirst().segments()),
                        tuple(b.toString(), true, List.of()));
        assertThat(targets().get(1).segmentsFile()).isEmpty();
    }

    @Test
    void aNewerSegmentsFileThatCantBeReadIsAnError() throws Exception {
        Path dir = writeSegments(tmp.resolve("index"), 2);
        currentIndex.replace(TestIndexes.open(dir));
        // A commit that came after, which the check would read and this list can't.
        Files.writeString(dir.resolve("segments_9"), "not a segments file");

        assertThat(targets()).singleElement().satisfies(index -> {
            assertThat(index.segments()).isEmpty();
            assertThat(index.segmentsFile()).isEmpty();
            assertThat(index.error())
                    .hasValueSatisfying(error -> assertThat(error).contains("segments_9", "can't be read"));
        });
    }

    @Test
    void anOlderSegmentsFileThatCantBeReadIsNoError() throws Exception {
        Path dir = writeSegments(tmp.resolve("index"), 2);
        currentIndex.replace(TestIndexes.open(dir));
        Files.writeString(dir.resolve("segments_1"), "not a segments file");

        assertThat(targets()).singleElement().satisfies(index -> {
            assertThat(index.error()).isEmpty();
            assertThat(index.segments()).hasSize(2);
        });
    }

    @Test
    void failsWithoutOpenIndex() {
        assertThatThrownBy(this::targets)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NO_INDEX_OPEN));
    }

    /** A commit and a segment for each of {@code segments}, with two documents each. */
    private static Path writeSegments(Path dir, int segments) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            for (int segment = 0; segment < segments; segment++) {
                for (int id = 1; id <= 2; id++) {
                    Document doc = new Document();
                    doc.add(new StringField("id", segment + "-" + id, Field.Store.YES));
                    writer.addDocument(doc);
                }
                writer.commit();
            }
        }
        return dir;
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

    private static void deleteSegmentsFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith("segments_"))
                    .toList()) {
                Files.delete(file);
            }
        }
    }
}
