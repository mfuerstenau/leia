package io.github.mfuerstenau.leia.server.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.terms.ExportTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetExportTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.StartExportTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.TermDelimiter;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.TestIndexes;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.IndexDirectories;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code startExportTerms} and {@code getExportTerms} together (ADR 0022). */
class ExportTermsHandlersTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path tmp;

    private final Jobs jobs = new Jobs();
    private final CurrentIndex currentIndex = new CurrentIndex(jobs, new Filters());
    private final StartExportTermsHandler start =
            new StartExportTermsHandler(currentIndex, new IndexDirectories(currentIndex, new Runtimes()), jobs);
    private final GetExportTermsHandler get = new GetExportTermsHandler(jobs);

    @Test
    void writesTheTermsToTheFile() throws Exception {
        openIndex();
        Path file = tmp.resolve("terms.out");

        long jobId = start.handle(new StartExportTermsParams("body", file.toString()))
                .jobId();

        assertThat(awaitDone(jobId))
                .isEqualTo(new GetExportTermsResult(
                        3, Optional.of(new ExportTermsResult(file.toString(), 3, Files.size(file)))));
        assertThat(Files.readString(file)).isEqualTo("blue\t1\ngreen\t1\nred\t2\n");
        assertFails(() -> get.handle(new JobParams(jobId)), ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    void writesTheChosenDelimiterAndOverwrites() throws Exception {
        openIndex();
        Path file = Files.writeString(tmp.resolve("terms.out"), "old");

        long jobId = start.handle(new StartExportTermsParams(
                        "body", file.toString(), Optional.of(TermDelimiter.COMMA), Optional.of(true)))
                .jobId();
        awaitDone(jobId);

        assertThat(Files.readString(file)).isEqualTo("blue,1\ngreen,1\nred,2\n");
    }

    @Test
    void refusesAFileThatExists() throws Exception {
        openIndex();
        Path file = Files.writeString(tmp.resolve("terms.out"), "old");

        assertThatThrownBy(() -> start.handle(new StartExportTermsParams("body", file.toString())))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.OUTPUT_NOT_WRITABLE))
                .hasMessage("File exists: " + file);
    }

    @Test
    void refusesToWriteIntoTheIndexDirectory() throws Exception {
        Path dir = openIndex();

        assertThatThrownBy(() -> start.handle(new StartExportTermsParams(
                        "body", dir.resolve("terms.out").toString())))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.OUTPUT_NOT_WRITABLE))
                .hasMessage("Not writing into the index directory " + dir + ": leia never changes an index");
        assertThat(dir.resolve("terms.out")).doesNotExist();
    }

    @Test
    void rejectsARelativePath() throws Exception {
        openIndex();

        assertFails(() -> start.handle(new StartExportTermsParams("body", "terms.out")), ErrorCode.INVALID_PARAMS);
    }

    @Test
    void failsForUnknownField() throws Exception {
        openIndex();

        assertFails(
                () -> start.handle(
                        new StartExportTermsParams("bdy", tmp.resolve("t.out").toString())),
                ErrorCode.FIELD_NOT_FOUND);
    }

    @Test
    void needsAReader() throws Exception {
        Path dir = openIndex();
        currentIndex.replace(TestIndexes.withoutReader(dir, DirectoryKind.FS));

        assertFails(
                () -> start.handle(
                        new StartExportTermsParams("body", tmp.resolve("t.out").toString())),
                ErrorCode.NO_READER);
    }

    @Test
    void failsWithoutOpenIndex() {
        assertFails(
                () -> start.handle(
                        new StartExportTermsParams("body", tmp.resolve("t.out").toString())),
                ErrorCode.NO_INDEX_OPEN);
    }

    /** One segment: "red blue red" and "red green". */
    private Path openIndex() throws Exception {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (String body : List.of("red blue red", "red green")) {
                Document doc = new Document();
                doc.add(new TextField("body", body, Field.Store.NO));
                writer.addDocument(doc);
            }
        }
        currentIndex.replace(TestIndexes.open(dir));
        return dir;
    }

    private GetExportTermsResult awaitDone(long jobId) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            GetExportTermsResult result = get.handle(new JobParams(jobId));
            if (result.result().isPresent()) {
                return result;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    private static void assertFails(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
