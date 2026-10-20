package io.github.mfuerstenau.leia.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.analysis.AutoStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.FieldStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.GetAutoStopWordsResult;
import io.github.mfuerstenau.leia.protocol.analysis.StartAutoStopWordsParams;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.TestIndexes;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
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

/** {@code startAutoStopWords} and {@code getAutoStopWords} together (ADR 0039). */
class AutoStopWordsHandlersTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path tmp;

    private final Jobs jobs = new Jobs();
    private final CurrentIndex currentIndex = new CurrentIndex(jobs, new Filters());
    private final StartAutoStopWordsHandler start = new StartAutoStopWordsHandler(currentIndex, jobs);
    private final GetAutoStopWordsHandler get = new GetAutoStopWordsHandler(jobs);

    @Test
    void findsTheWordsOfEveryIndexedField() throws Exception {
        currentIndex.replace(TestIndexes.open(writeIndex()));

        // 4 documents: 40 % is 1, so a word in 2 or more.
        GetAutoStopWordsResult result =
                awaitDone(start.handle(percent(List.of(), 0.4)).jobId());

        assertThat(result.stopWords())
                .hasValue(new AutoStopWords(
                        1,
                        List.of(
                                new FieldStopWords("body", List.of("blue", "red", "the")),
                                new FieldStopWords("id", List.of()))));
        // red, blue, the, green; then the four ids.
        assertThat(result.termsScanned()).isEqualTo(8);
    }

    @Test
    void findsTheWordsOfTheGivenFields() throws Exception {
        currentIndex.replace(TestIndexes.open(writeIndex()));

        GetAutoStopWordsResult result = awaitDone(
                start.handle(new StartAutoStopWordsParams(List.of("body"), OptionalInt.of(2), OptionalDouble.empty()))
                        .jobId());

        assertThat(result.stopWords())
                .hasValue(new AutoStopWords(2, List.of(new FieldStopWords("body", List.of("the")))));
    }

    @Test
    void checksTheThresholdAndTheFields() throws Exception {
        currentIndex.replace(TestIndexes.open(writeIndex()));

        for (StartAutoStopWordsParams params : List.of(
                new StartAutoStopWordsParams(List.of(), OptionalInt.empty(), OptionalDouble.empty()),
                new StartAutoStopWordsParams(List.of(), OptionalInt.of(1), OptionalDouble.of(0.4)),
                new StartAutoStopWordsParams(List.of(), OptionalInt.of(-1), OptionalDouble.empty()),
                percent(List.of(), 1.5),
                percent(List.of(), Double.NaN))) {
            assertFails(() -> start.handle(params), ErrorCode.INVALID_PARAMS);
        }
        for (String field : List.of("nope", "rank")) {
            assertThatThrownBy(() -> start.handle(percent(List.of(field), 0.4)))
                    .isInstanceOfSatisfying(
                            RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_NOT_FOUND))
                    .hasMessage("No indexed field '" + field + "' in the index");
        }
    }

    @Test
    void tooManyWordsFailTheJob() throws Exception {
        Path dir = tmp.resolve("many");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            Document doc = new Document();
            StringBuilder body = new StringBuilder();
            for (int i = 0; i <= StartAutoStopWordsParams.MAX_WORDS; i++) {
                body.append("w").append(i).append(' ');
            }
            doc.add(new TextField("body", body.toString(), Field.Store.NO));
            writer.addDocument(doc);
        }
        currentIndex.replace(TestIndexes.open(dir));

        long jobId = start.handle(new StartAutoStopWordsParams(List.of(), OptionalInt.of(0), OptionalDouble.empty()))
                .jobId();

        RpcException failure = awaitFailure(jobId);
        assertThat(failure.code()).isEqualTo(ErrorCode.INVALID_PARAMS);
        assertThat(failure.getMessage())
                .isEqualTo("Invalid params: field 'body' has more than 10000 stop words; raise the threshold");
    }

    @Test
    void needsAReader() throws Exception {
        assertFails(() -> start.handle(percent(List.of(), 0.4)), ErrorCode.NO_INDEX_OPEN);
        currentIndex.replace(TestIndexes.withoutReader(writeIndex(), DirectoryKind.FS));
        assertFails(() -> start.handle(percent(List.of(), 0.4)), ErrorCode.NO_READER);
    }

    private static StartAutoStopWordsParams percent(List<String> fields, double fraction) {
        return new StartAutoStopWordsParams(fields, OptionalInt.empty(), OptionalDouble.of(fraction));
    }

    /** Four documents; {@code rank} has doc values only. */
    private Path writeIndex() throws Exception {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            int id = 1;
            for (String body : List.of("the red", "the red blue", "the green", "blue")) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(id++), Field.Store.NO));
                doc.add(new TextField("body", body, Field.Store.NO));
                doc.add(new NumericDocValuesField("rank", id));
                writer.addDocument(doc);
            }
        }
        return dir;
    }

    private GetAutoStopWordsResult awaitDone(long jobId) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            GetAutoStopWordsResult result = get.handle(new JobParams(jobId));
            if (result.stopWords().isPresent()) {
                return result;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    private RpcException awaitFailure(long jobId) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                get.handle(new JobParams(jobId));
            } catch (RpcException e) {
                return e;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("job " + jobId + " did not fail");
    }

    private static void assertFails(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
