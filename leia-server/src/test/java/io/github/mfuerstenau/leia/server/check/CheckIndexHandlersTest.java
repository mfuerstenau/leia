package io.github.mfuerstenau.leia.server.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.check.CheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.IndexCheck;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code startCheckIndex} and {@code getCheckIndex} together (ADR 0022). */
class CheckIndexHandlersTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tmp;

    private final Jobs jobs = new Jobs();
    private final CurrentIndex currentIndex = new CurrentIndex(jobs, new Filters());
    private final StartCheckIndexHandler start =
            new StartCheckIndexHandler(currentIndex, new IndexDirectories(currentIndex, new Runtimes()), jobs);
    private final GetCheckIndexHandler get = new GetCheckIndexHandler(jobs);

    @Test
    void checksTheIndexAndStreamsTheOutput() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        currentIndex.replace(TestIndexes.open(dir));

        long jobId = start.handle(new StartCheckIndexParams(OptionalInt.of(2))).jobId();
        List<String> lines = new ArrayList<>();
        CheckIndexResult result = awaitDone(jobId, lines);

        assertThat(lines.getFirst()).isEqualTo("Checking " + dir);
        assertThat(lines).contains("No problems were detected with this index.");
        assertThat(result.indexes()).hasSize(1);
        IndexCheck check = result.indexes().getFirst();
        assertThat(check.path()).isEqualTo(dir.toString());
        assertThat(check.skipped()).isFalse();
        assertThat(check.clean()).isTrue();
        assertThat(check.segmentsFile()).contains("segments_1");
        assertThat(check.segments()).hasSize(1);
        assertThat(check.segments().getFirst().maxDoc()).isEqualTo(2);
        assertFails(() -> get.handle(new GetCheckIndexParams(jobId, 0)), ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    void checksTheSkippedIndexesToo() throws Exception {
        Path a = writeIndex(tmp.resolve("indexes/a"));
        Path b = corrupt(writeIndex(tmp.resolve("indexes/b")));
        currentIndex.replace(TestIndexes.open(tmp.resolve("indexes")));

        long jobId = start.handle(new StartCheckIndexParams()).jobId();
        List<String> lines = new ArrayList<>();
        CheckIndexResult result = awaitDone(jobId, lines);

        assertThat(lines).contains("Checking index 1 of 2: " + a, "Checking index 2 of 2: " + b);
        assertThat(result.indexes())
                .extracting(IndexCheck::path, IndexCheck::skipped, IndexCheck::clean, IndexCheck::missingSegments)
                .containsExactly(tuple(a.toString(), false, true, false), tuple(b.toString(), true, false, true));
    }

    @Test
    void checksAnIndexOpenedWithoutAReader() throws Exception {
        Path dir = corrupt(writeIndex(tmp.resolve("index")));
        currentIndex.replace(TestIndexes.withoutReader(dir, DirectoryKind.NIO));

        long jobId = start.handle(new StartCheckIndexParams()).jobId();
        CheckIndexResult result = awaitDone(jobId, new ArrayList<>());

        assertThat(result.indexes()).singleElement().satisfies(check -> {
            assertThat(check.clean()).isFalse();
            assertThat(check.missingSegments()).isTrue();
        });
    }

    @Test
    void returnsTheLinesFromTheGivenOne() throws Exception {
        currentIndex.replace(TestIndexes.open(writeIndex(tmp.resolve("index"))));

        GetCheckIndexResult fromThree =
                awaitOutcome(start.handle(new StartCheckIndexParams()).jobId(), 3);
        GetCheckIndexResult pastTheEnd =
                awaitOutcome(start.handle(new StartCheckIndexParams()).jobId(), 10_000);

        assertThat(fromThree.lines()).hasSize(fromThree.lineCount() - 3);
        assertThat(pastTheEnd.lines()).isEmpty();
        assertThat(pastTheEnd.lineCount()).isEqualTo(fromThree.lineCount());
    }

    @Test
    void cancellingStopsTheCheck() throws Exception {
        currentIndex.replace(TestIndexes.open(writeIndex(tmp.resolve("index"))));
        long jobId = start.handle(new StartCheckIndexParams(OptionalInt.of(3))).jobId();

        jobs.cancel(jobId);

        assertFails(() -> get.handle(new GetCheckIndexParams(jobId, 0)), ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    void failsWithoutOpenIndex() {
        assertFails(() -> start.handle(new StartCheckIndexParams()), ErrorCode.NO_INDEX_OPEN);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4})
    void rejectsALevelOutOfRange(int level) throws Exception {
        currentIndex.replace(TestIndexes.open(writeIndex(tmp.resolve("index"))));

        assertFails(() -> start.handle(new StartCheckIndexParams(OptionalInt.of(level))), ErrorCode.INVALID_PARAMS);
    }

    @Test
    void rejectsANegativeLine() throws Exception {
        currentIndex.replace(TestIndexes.open(writeIndex(tmp.resolve("index"))));
        long jobId = start.handle(new StartCheckIndexParams()).jobId();

        assertFails(() -> get.handle(new GetCheckIndexParams(jobId, -1)), ErrorCode.INVALID_PARAMS);
    }

    @Test
    void failsForUnknownJob() {
        assertFails(() -> get.handle(new GetCheckIndexParams(99, 0)), ErrorCode.JOB_NOT_FOUND);
    }

    /** Polls as the TUI does, collecting the lines, until the outcome is there. */
    private CheckIndexResult awaitDone(long jobId, List<String> lines) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            GetCheckIndexResult result = get.handle(new GetCheckIndexParams(jobId, lines.size()));
            lines.addAll(result.lines());
            assertThat(result.lineCount()).isEqualTo(lines.size());
            if (result.result().isPresent()) {
                return result.result().get();
            }
            Thread.sleep(5);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    /** Polls from {@code fromLine} on until the outcome is there. */
    private GetCheckIndexResult awaitOutcome(long jobId, int fromLine) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            GetCheckIndexResult result = get.handle(new GetCheckIndexParams(jobId, fromLine));
            if (result.result().isPresent()) {
                return result;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    /** One segment with two documents. */
    private static Path writeIndex(Path dir) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (int id = 1; id <= 2; id++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(id), Field.Store.YES));
                writer.addDocument(doc);
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

    private static void assertFails(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
