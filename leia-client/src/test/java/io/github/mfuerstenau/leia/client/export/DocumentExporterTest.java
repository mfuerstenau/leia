package io.github.mfuerstenau.leia.client.export;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.client.export.DocumentExport.DocumentData;
import io.github.mfuerstenau.leia.client.export.DocumentExporter.Request;
import io.github.mfuerstenau.leia.client.export.DocumentExporter.Target;
import io.github.mfuerstenau.leia.client.export.ExportJob.Snapshot;
import io.github.mfuerstenau.leia.client.export.ExportJob.State;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.StartReconstructParams;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import io.github.mfuerstenau.leia.server.RpcException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole export of a document (ADR 0030, ADR 0126): fetched, rebuilt, put together and written
 * or handed over as text, as a job that a UI polls.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DocumentExporterTest {

    private static LeiaBackend backend;
    private static String demo;
    private static Function<String, Optional<IndexField>> schema;

    @TempDir
    Path tmp;

    @BeforeAll
    static void open() throws Exception {
        backend = LeiaBackend.start(Optional.empty(), List.of());
        demo = Path.of(System.getProperty("leia.test.index.demo"))
                .toAbsolutePath()
                .normalize()
                .toString();
        backend.call(Methods.OPEN_INDEX, new OpenIndexParams(demo)).get();
        List<IndexField> fields = backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams())
                .get()
                .fields();
        schema = name ->
                fields.stream().filter(field -> field.name().equals(name)).findFirst();
    }

    @AfterAll
    static void close() {
        backend.close();
    }

    private static Request request(int docId, boolean indexedTerms, Target target) {
        return new Request(docId, true, indexedTerms, schema, demo, Optional.empty(), target);
    }

    @Test
    void writesWhatTheFunctionsPutTogether() throws Exception {
        Path file = tmp.resolve("doc-1.json");
        DocumentExporter exporter = new DocumentExporter(DocumentExporter.Calls.of(backend));

        Snapshot done = exporter.start(request(1, true, new Target.File(file, false, List.of(demo))))
                .done()
                .get(30, TimeUnit.SECONDS);

        assertThat(done.state()).isEqualTo(State.DONE);
        assertThat(done.path()).contains(file);
        assertThat(done.error()).isEmpty();
        DocumentData data = DocumentExport.load(DocumentExporter.Calls.of(backend), 1, true)
                .toCompletableFuture()
                .get();
        assertThat(Files.readString(file))
                .isEqualTo(DocumentExport.toJson(
                        DocumentExport.assemble(data, Optional.of(rebuilt(1)), schema, demo, Optional.empty())));
        assertThat(DocumentExport.fromJson(Files.readString(file)).indexedTerms())
                .isTrue();
    }

    @Test
    void handsOverTheTextWithoutRebuildingWhenNotAsked() throws Exception {
        DocumentExporter exporter = new DocumentExporter(DocumentExporter.Calls.of(backend));

        Snapshot done =
                exporter.start(request(2, false, new Target.Text())).done().get(30, TimeUnit.SECONDS);

        assertThat(done.state()).isEqualTo(State.DONE);
        assertThat(done.path()).isEmpty();
        DocumentData data = DocumentExport.load(DocumentExporter.Calls.of(backend), 2, true)
                .toCompletableFuture()
                .get();
        assertThat(done.text())
                .contains(DocumentExport.toJson(
                        DocumentExport.assemble(data, Optional.empty(), schema, demo, Optional.empty())));
        try (var files = Files.list(tmp)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void neverWritesIntoTheIndexDirectory() throws Exception {
        Path file = Path.of(demo).resolve("doc-1.json");
        DocumentExporter exporter = new DocumentExporter(DocumentExporter.Calls.of(backend));

        Snapshot done = exporter.start(request(1, false, new Target.File(file, false, List.of(demo))))
                .done()
                .get(30, TimeUnit.SECONDS);

        assertThat(done.state()).isEqualTo(State.FAILED);
        assertThat(done.error())
                .hasValueSatisfying(error -> assertThat(error).contains("Not writing into the index directory"));
        assertThat(done.cause()).isEmpty();
        assertThat(file).doesNotExist();
    }

    @Test
    void overwritesAFileOnlyWhenTold() throws Exception {
        Path file = tmp.resolve("doc-1.json");
        Files.writeString(file, "before");
        DocumentExporter exporter = new DocumentExporter(DocumentExporter.Calls.of(backend));

        Snapshot refused = exporter.start(request(1, false, new Target.File(file, false, List.of(demo))))
                .done()
                .get(30, TimeUnit.SECONDS);
        assertThat(refused.state()).isEqualTo(State.FAILED);
        assertThat(Files.readString(file)).isEqualTo("before");

        Snapshot written = exporter.start(request(1, false, new Target.File(file, true, List.of(demo))))
                .done()
                .get(30, TimeUnit.SECONDS);
        assertThat(written.state()).isEqualTo(State.DONE);
        assertThat(Files.readString(file)).startsWith("{");
    }

    @Test
    void showsTheRebuildsProgressAndACancelStopsItsJobAndWritesNothing() throws Exception {
        FakeExportCalls calls = fakeCalls();
        Path file = tmp.resolve("doc-0.json");
        ExportJob job = new DocumentExporter(calls, Duration.ofMillis(10))
                .start(new Request(
                        0,
                        true,
                        true,
                        name -> Optional.empty(),
                        "/index",
                        Optional.empty(),
                        new Target.File(file, false, List.of("/index"))));

        Snapshot rebuilding = awaitSnapshot(job, snapshot -> snapshot.scanned() == 5);
        assertThat(rebuilding.state()).isEqualTo(State.REBUILDING);
        assertThat(rebuilding.total()).hasValue(10);

        job.cancel();

        Snapshot done = job.done().get(30, TimeUnit.SECONDS);
        assertThat(done.state()).isEqualTo(State.CANCELLED);
        assertThat(job.snapshot().state()).isEqualTo(State.CANCELLED);
        assertThat(calls.cancelled).containsExactly(7L);
        assertThat(file).doesNotExist();
    }

    @Test
    void aCancelBeforeTheRebuildHasStartedCancelsItsJobOnceItHas() throws Exception {
        FakeExportCalls calls = fakeCalls();
        calls.started = new CompletableFuture<>();
        ExportJob job = new DocumentExporter(calls, Duration.ofMillis(10))
                .start(new Request(
                        0, true, true, name -> Optional.empty(), "/index", Optional.empty(), new Target.Text()));
        awaitSnapshot(job, snapshot -> snapshot.state() == State.REBUILDING);

        job.cancel();
        calls.started.complete(7L);

        assertThat(job.done().get(30, TimeUnit.SECONDS).state()).isEqualTo(State.CANCELLED);
        awaitTrue(() -> calls.cancelled.contains(7L));
    }

    @Test
    void aCallThatFailsFailsTheExportWithItsCause() throws Exception {
        FakeExportCalls calls = fakeCalls();
        calls.document = CompletableFuture.failedFuture(new RpcException(ErrorCode.NO_INDEX_OPEN, "no index is open"));

        Snapshot done = new DocumentExporter(calls)
                .start(new Request(
                        0, true, false, name -> Optional.empty(), "/index", Optional.empty(), new Target.Text()))
                .done()
                .get(30, TimeUnit.SECONDS);

        assertThat(done.state()).isEqualTo(State.FAILED);
        assertThat(done.error()).contains("no index is open");
        assertThat(done.cause()).containsInstanceOf(RpcException.class);
    }

    /** The rebuild of {@code docId}, as the job of the backend gives it. */
    private static List<ReconstructedField> rebuilt(int docId) throws Exception {
        long job = backend.call(Methods.START_RECONSTRUCT, new StartReconstructParams(docId, Optional.empty()))
                .get()
                .jobId();
        while (true) {
            GetReconstructResult result =
                    backend.call(Methods.GET_RECONSTRUCT, new JobParams(job)).get();
            if (result.result().isPresent()) {
                return result.result().get().fields();
            }
            Thread.sleep(10);
        }
    }

    private static Snapshot awaitSnapshot(ExportJob job, java.util.function.Predicate<Snapshot> condition)
            throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < end) {
            Snapshot snapshot = job.snapshot();
            if (condition.test(snapshot)) {
                return snapshot;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("no such snapshot: " + job.snapshot());
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("in time").isLessThan(end);
            Thread.sleep(5);
        }
    }

    private static FakeExportCalls fakeCalls() {
        return new FakeExportCalls(new GetDocumentResult(
                0,
                false,
                List.of(new DocumentField(
                        "title",
                        OptionalLong.empty(),
                        List.of(new StoredValue(StoredType.STRING, "kettle", false)),
                        Optional.empty(),
                        false,
                        Optional.empty()))));
    }
}
