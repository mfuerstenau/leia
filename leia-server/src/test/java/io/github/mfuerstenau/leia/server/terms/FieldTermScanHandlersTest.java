package io.github.mfuerstenau.leia.server.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.terms.FieldTerms;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.protocol.terms.StartFieldTermScanParams;
import io.github.mfuerstenau.leia.protocol.terms.TopTerm;
import io.github.mfuerstenau.leia.server.ByteStrings;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.TestIndexes;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code startFieldTermScan} and {@code getFieldTermScan} together. */
class FieldTermScanHandlersTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path tmp;

    private final Jobs jobs = new Jobs();
    private final CurrentIndex currentIndex = new CurrentIndex(jobs, new Filters());
    private final StartFieldTermScanHandler start = new StartFieldTermScanHandler(currentIndex, jobs);
    private final GetFieldTermScanHandler get = new GetFieldTermScanHandler(jobs);

    @Test
    void scansAField() throws Exception {
        openIndex();

        long jobId = start.handle(new StartFieldTermScanParams("body", 2)).jobId();

        assertThat(awaitDone(jobId))
                .isEqualTo(new GetFieldTermScanResult(
                        3,
                        Optional.of(new FieldTerms(
                                "body",
                                3,
                                List.of(new TopTerm("red", false, 2, 3), new TopTerm("blue", false, 1, 1))))));
        assertFails(() -> get.handle(new JobParams(jobId)), ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    void failsWithoutOpenIndex() {
        assertFails(() -> start.handle(new StartFieldTermScanParams("body", 10)), ErrorCode.NO_INDEX_OPEN);
    }

    @Test
    void failsForUnknownField() throws Exception {
        openIndex();

        assertThatThrownBy(() -> start.handle(new StartFieldTermScanParams("bdy", 10)))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_NOT_FOUND))
                .hasMessage("No field 'bdy' in the index");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, StartFieldTermScanParams.MAX_TOP_N + 1})
    void rejectsTopNOutOfRange(int topN) throws Exception {
        openIndex();

        assertFails(() -> start.handle(new StartFieldTermScanParams("body", topN)), ErrorCode.INVALID_PARAMS);
    }

    @Test
    void acceptsTheLargestTopN() throws Exception {
        openIndex();

        long jobId = start.handle(new StartFieldTermScanParams("body", StartFieldTermScanParams.MAX_TOP_N))
                .jobId();

        assertThat(awaitDone(jobId).terms().orElseThrow().topTerms()).hasSize(3);
    }

    @Test
    void failsForUnknownJob() throws IOException {
        assertFails(() -> get.handle(new JobParams(99)), ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    void sendsBinaryTermsAsHex() {
        assertThat(ByteStrings.encode(new byte[] {(byte) 0xff, 0x00, 0x41}))
                .isEqualTo(new ByteStrings.Encoded("ff0041", true));
        assertThat(ByteStrings.encode("grüße".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(new ByteStrings.Encoded("grüße", false));
    }

    /** One segment: "red blue red" and "red green". */
    private void openIndex() throws Exception {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            int id = 1;
            for (String body : List.of("red blue red", "red green")) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(id++), Field.Store.NO));
                doc.add(new TextField("body", body, Field.Store.NO));
                writer.addDocument(doc);
            }
        }
        currentIndex.replace(TestIndexes.open(dir));
    }

    private GetFieldTermScanResult awaitDone(long jobId) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            GetFieldTermScanResult result = get.handle(new JobParams(jobId));
            if (result.terms().isPresent()) {
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
