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
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
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
        backend = LeiaBackend.start(null, List.of());
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
        return new Request(docId, true, indexedTerms, schema, demo, null, target);
    }

    @Test
    void theBackendsCallsFetchAWholeStoredValueAndCancelAJob() throws Exception {
        DocumentExporter.Calls calls = DocumentExporter.Calls.of(backend);
        DocumentField field = calls.document(1).toCompletableFuture().get().fields().stream()
                .filter(f -> !f.stored().isEmpty())
                .findFirst()
                .orElseThrow();

        StoredValue value =
                calls.storedValue(1, field.name(), 0).toCompletableFuture().get();
        long job = calls.startReconstruct(1).toCompletableFuture().get();

        assertThat(value.value()).isEqualTo(field.stored().getFirst().value());
        assertThat(value.fullLength()).isEmpty();
        assertThat(calls.cancelJob(job).toCompletableFuture().get()).isNull();
    }

    @Test
    void writesWhatTheFunctionsPutTogether() throws Exception {
        Path file = tmp.resolve("doc-1.json");
        DocumentExporter exporter = new DocumentExporter(DocumentExporter.Calls.of(backend));

        Snapshot done = exporter.start(request(1, true, new Target.File(file, false, List.of(demo))))
                .done()
                .get(30, TimeUnit.SECONDS);

        assertThat(done.state()).isEqualTo(State.DONE);
        assertThat(done.path()).isEqualTo(file);
        assertThat(done.error()).isNull();
        DocumentData data = DocumentExport.load(DocumentExporter.Calls.of(backend), 1, true)
                .toCompletableFuture()
                .get();
        assertThat(Files.readString(file))
                .isEqualTo(DocumentExport.toJson(DocumentExport.assemble(data, rebuilt(1), schema, demo, null)));
        assertThat(DocumentExport.fromJson(Files.readString(file)).indexedTerms())
                .isTrue();
    }

    @Test
    void handsOverTheTextWithoutRebuildingWhenNotAsked() throws Exception {
        DocumentExporter exporter = new DocumentExporter(DocumentExporter.Calls.of(backend));

        Snapshot done =
                exporter.start(request(2, false, new Target.Text())).done().get(30, TimeUnit.SECONDS);

        assertThat(done.state()).isEqualTo(State.DONE);
        assertThat(done.path()).isNull();
        DocumentData data = DocumentExport.load(DocumentExporter.Calls.of(backend), 2, true)
                .toCompletableFuture()
                .get();
        assertThat(done.text())
                .isEqualTo(DocumentExport.toJson(DocumentExport.assemble(data, null, schema, demo, null)));
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
        assertThat(done.error()).contains("Not writing into the index directory");
        assertThat(done.cause()).isNull();
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
                        null,
                        new Target.File(file, false, List.of("/index"))));

        Snapshot rebuilding = awaitSnapshot(job, snapshot -> snapshot.scanned() == 5);
        assertThat(rebuilding.state()).isEqualTo(State.REBUILDING);
        assertThat(rebuilding.total()).isEqualTo(10L);

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
                .start(new Request(0, true, true, name -> Optional.empty(), "/index", null, new Target.Text()));
        awaitSnapshot(job, snapshot -> snapshot.state() == State.REBUILDING);

        job.cancel();
        calls.started.complete(7L);

        assertThat(job.done().get(30, TimeUnit.SECONDS).state()).isEqualTo(State.CANCELLED);
        assertThat(calls.firstCancelled.get(30, TimeUnit.SECONDS)).isEqualTo(7L);
    }

    @Test
    void aCallThatFailsFailsTheExportWithItsCause() throws Exception {
        FakeExportCalls calls = fakeCalls();
        calls.document = CompletableFuture.failedFuture(new RpcException(ErrorCode.NO_INDEX_OPEN, "no index is open"));

        Snapshot done = new DocumentExporter(calls)
                .start(new Request(0, true, false, name -> Optional.empty(), "/index", null, new Target.Text()))
                .done()
                .get(30, TimeUnit.SECONDS);

        assertThat(done.state()).isEqualTo(State.FAILED);
        assertThat(done.error()).isEqualTo("no index is open");
        assertThat(done.cause()).isInstanceOf(RpcException.class);
    }

    @Test
    void failsWhenTheRebuildCantStartOrBeAskedOrTheDocumentCantBePutTogether() throws Exception {
        FakeExportCalls notStarted = fakeCalls();
        notStarted.started = CompletableFuture.failedFuture(new IllegalStateException());
        FakeExportCalls notAsked = fakeCalls();
        notAsked.reconstructCall =
                CompletableFuture.failedFuture(new CompletionException(new IllegalStateException("the job is gone")));

        Snapshot notStartedDone = exportText(notStarted, true, name -> Optional.empty());
        Snapshot notAskedDone = exportText(notAsked, true, name -> Optional.empty());
        Snapshot unassembled = exportText(fakeCalls(), false, name -> {
            throw new IllegalArgumentException("no schema");
        });

        assertThat(notStartedDone.state()).isEqualTo(State.FAILED);
        assertThat(notStartedDone.error()).isEqualTo(IllegalStateException.class.getName());
        assertThat(notAskedDone.state()).isEqualTo(State.FAILED);
        assertThat(notAskedDone.error()).isEqualTo("the job is gone");
        assertThat(notAskedDone.cause()).isInstanceOf(IllegalStateException.class);
        assertThat(unassembled.state()).isEqualTo(State.FAILED);
        assertThat(unassembled.error()).isEqualTo("no schema");
    }

    @Test
    void aCancelWhileTheDocumentLoadsEndsItWhateverTheLoadDoesThen() throws Exception {
        for (boolean indexedTerms : List.of(true, false)) {
            FakeExportCalls loaded = fakeCalls();
            CompletableFuture<GetDocumentResult> document = new CompletableFuture<>();
            GetDocumentResult result = loaded.document.toCompletableFuture().get();
            loaded.document = document;
            ExportJob job = new DocumentExporter(loaded).start(textRequest(indexedTerms, name -> Optional.empty()));

            job.cancel();
            document.complete(result);

            assertThat(job.done().get(30, TimeUnit.SECONDS).state()).isEqualTo(State.CANCELLED);
            assertThat(job.snapshot().state()).isEqualTo(State.CANCELLED);
            assertThat(loaded.cancelled).isEmpty();
        }
        FakeExportCalls failed = fakeCalls();
        CompletableFuture<GetDocumentResult> document = new CompletableFuture<>();
        failed.document = document;
        ExportJob job = new DocumentExporter(failed).start(textRequest(false, name -> Optional.empty()));

        job.cancel();
        document.completeExceptionally(new IllegalStateException("too late"));

        assertThat(job.snapshot().state()).isEqualTo(State.CANCELLED);
        assertThat(job.snapshot().error()).isNull();
    }

    @Test
    void aRebuildsAnswerAfterACancelIsDropped() {
        FakeExportCalls calls = fakeCalls();
        CompletableFuture<GetReconstructResult> answer = new CompletableFuture<>();
        calls.reconstructCall = answer;
        ExportJob job = new DocumentExporter(calls).start(textRequest(true, name -> Optional.empty()));
        assertThat(job.snapshot().state()).isEqualTo(State.REBUILDING);

        job.cancel();
        answer.complete(FakeExportCalls.running(5, 10));

        assertThat(job.snapshot().state()).isEqualTo(State.CANCELLED);
        assertThat(job.snapshot().scanned()).isZero();
        assertThat(calls.cancelled).containsExactly(7L);
    }

    @Test
    void aCancelAfterTheEndChangesNothing() throws Exception {
        ExportJob job = new DocumentExporter(fakeCalls()).start(textRequest(false, name -> Optional.empty()));
        Snapshot done = job.done().get(30, TimeUnit.SECONDS);

        job.cancel();

        assertThat(done.state()).isEqualTo(State.DONE);
        assertThat(job.snapshot()).isEqualTo(done);
    }

    private static Request textRequest(boolean indexedTerms, Function<String, Optional<IndexField>> schema) {
        return new Request(0, true, indexedTerms, schema, "/index", null, new Target.Text());
    }

    private static Snapshot exportText(
            FakeExportCalls calls, boolean indexedTerms, Function<String, Optional<IndexField>> schema)
            throws Exception {
        return new DocumentExporter(calls, Duration.ofMillis(10))
                .start(textRequest(indexedTerms, schema))
                .done()
                .get(30, TimeUnit.SECONDS);
    }

    /** The rebuild of {@code docId}, as the job of the backend gives it. */
    private static List<ReconstructedField> rebuilt(int docId) throws Exception {
        long job = backend.call(Methods.START_RECONSTRUCT, new StartReconstructParams(docId, Optional.empty()))
                .get()
                .jobId();
        // Asked again 10 ms after each answer, until the rebuild is done
        Executor later = CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS);
        GetReconstructResult result =
                backend.call(Methods.GET_RECONSTRUCT, new JobParams(job)).get();
        while (result.result().isEmpty()) {
            result = CompletableFuture.supplyAsync(
                            () -> backend.call(Methods.GET_RECONSTRUCT, new JobParams(job)), later)
                    .thenCompose(Function.identity())
                    .get();
        }
        return result.result().orElseThrow().fields();
    }

    private static Snapshot awaitSnapshot(ExportJob job, java.util.function.Predicate<Snapshot> condition) {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < end) {
            Snapshot snapshot = job.snapshot();
            if (condition.test(snapshot)) {
                return snapshot;
            }
            // Until the export ends, or for 5 ms
            job.done()
                    .copy()
                    .completeOnTimeout(snapshot, 5, TimeUnit.MILLISECONDS)
                    .join();
        }
        throw new AssertionError("no such snapshot: " + job.snapshot());
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
