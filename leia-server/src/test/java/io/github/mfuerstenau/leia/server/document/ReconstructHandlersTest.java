package io.github.mfuerstenau.leia.server.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.DirectoryImpl;
import io.github.mfuerstenau.leia.core.DirectoryOnlyIndex;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.ProtocolJson;
import io.github.mfuerstenau.leia.protocol.Response;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructSource;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.StartReconstructParams;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code startReconstruct} and {@code getReconstruct} together (ADR 0029). */
class ReconstructHandlersTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path tmp;

    private final Jobs jobs = new Jobs();
    private final CurrentIndex currentIndex = new CurrentIndex(jobs, new Filters());
    private final StartReconstructHandler start = new StartReconstructHandler(currentIndex, jobs);
    private final GetReconstructHandler get = new GetReconstructHandler(jobs);

    @Test
    void rebuildsTheGivenFields() throws Exception {
        currentIndex.replace(ReadOnlyIndex.open(writeIndex()));

        long jobId = start.handle(new StartReconstructParams(0, Optional.of(List.of("title", "id"))))
                .jobId();
        GetReconstructResult result = awaitDone(jobId);

        // "title" has 2 terms in the segment, "id" 2.
        assertThat(result.scannedTerms()).isEqualTo(4);
        assertThat(result.totalTerms()).isEqualTo(OptionalLong.of(4));
        assertThat(result.result().orElseThrow().fields())
                .containsExactly(
                        new ReconstructedField(
                                "title",
                                IndexOptions.DOCS_AND_FREQS_AND_POSITIONS,
                                ReconstructSource.POSTINGS,
                                List.of(new TermVectorTerm("red", false, 1, List.of(position(0))))),
                        new ReconstructedField(
                                "id",
                                IndexOptions.DOCS,
                                ReconstructSource.POSTINGS,
                                List.of(new TermVectorTerm("1", false, 1, List.of()))));
        assertFails(() -> get.handle(new JobParams(jobId)), ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    void rebuildsEveryIndexedFieldWithoutFields() throws Exception {
        currentIndex.replace(ReadOnlyIndex.open(writeIndex()));

        long jobId =
                start.handle(new StartReconstructParams(1, Optional.empty())).jobId();

        assertThat(awaitDone(jobId).result().orElseThrow().fields())
                .extracting(ReconstructedField::field)
                .containsExactly("id", "title");
    }

    @Test
    void aCancelledJobIsGone() throws Exception {
        String words = IntStream.range(0, 200_000).mapToObj(i -> "w" + i).collect(Collectors.joining(" "));
        currentIndex.replace(ReadOnlyIndex.open(writeIndex(words)));

        long jobId = start.handle(new StartReconstructParams(0, Optional.of(List.of("title"))))
                .jobId();
        jobs.cancel(jobId);

        assertFails(() -> get.handle(new JobParams(jobId)), ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    void checksTheParamsAtOnce() throws Exception {
        currentIndex.replace(ReadOnlyIndex.open(writeIndex()));

        assertFails(() -> start.handle(new StartReconstructParams(2, Optional.empty())), ErrorCode.DOC_NOT_FOUND);
        assertThatThrownBy(() -> start.handle(new StartReconstructParams(0, Optional.of(List.of("nope")))))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_NOT_FOUND))
                .hasMessage("No field 'nope' in the index");
        assertThatThrownBy(() -> start.handle(new StartReconstructParams(0, Optional.of(List.of("number")))))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMS))
                .hasMessage("Invalid params: the field 'number' is not indexed, so it has no terms to rebuild");
        assertThat(errorCodeOf("startReconstruct.error-field-not-found.json")).isEqualTo(ErrorCode.FIELD_NOT_FOUND);
        assertThat(errorCodeOf("startReconstruct.error-not-indexed.json")).isEqualTo(ErrorCode.INVALID_PARAMS);
    }

    @Test
    void needsAReader() throws Exception {
        currentIndex.replace(DirectoryOnlyIndex.open(writeIndex(), DirectoryImpl.FS));

        assertFails(() -> start.handle(new StartReconstructParams(0, Optional.empty())), ErrorCode.NO_READER);
    }

    @Test
    void failsWithoutOpenIndex() {
        assertFails(() -> start.handle(new StartReconstructParams(0, Optional.empty())), ErrorCode.NO_INDEX_OPEN);
    }

    private static TermPosition position(int position) {
        return new TermPosition(OptionalInt.of(position), OptionalInt.empty(), OptionalInt.empty(), Optional.empty());
    }

    /** One segment: "red" with id 1, "blue" with id 2; "number" has doc values only. */
    private Path writeIndex() throws IOException {
        return writeIndex("blue");
    }

    private Path writeIndex(String second) throws IOException {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            int id = 1;
            for (String title : List.of("red", second)) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(id++), Field.Store.NO));
                doc.add(new TextField("title", title, Field.Store.NO));
                doc.add(new NumericDocValuesField("number", id));
                writer.addDocument(doc);
            }
        }
        return dir;
    }

    private GetReconstructResult awaitDone(long jobId) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            GetReconstructResult result = get.handle(new JobParams(jobId));
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

    private static ErrorCode errorCodeOf(String fixture) throws IOException {
        String json = Files.readString(Path.of(System.getProperty("leia.protocol.dir"))
                .resolve("fixtures")
                .resolve(fixture));
        Response<?> response = ProtocolJson.mapper().readValue(json, ProtocolJson.responseType(Object.class));
        return response.error().orElseThrow().code();
    }
}
