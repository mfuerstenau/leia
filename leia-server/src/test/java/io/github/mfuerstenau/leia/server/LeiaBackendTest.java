package io.github.mfuerstenau.leia.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.terms.FieldTerms;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.protocol.terms.StartFieldTermScanParams;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.server.jars.JarChain;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeiaBackendTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tmp;

    @Test
    void runsTheCallsInOrderOnTheBackendThread() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        Handlers handlers = TestHandlers.with(TestHandlers.handler(Methods.OPEN_INDEX, params -> {
            paths.add(params.path());
            threads.add(Thread.currentThread());
            return null;
        }));

        try (LeiaBackend backend = new LeiaBackend(handlers, () -> {})) {
            backend.call(Methods.OPEN_INDEX, new OpenIndexParams("a"));
            backend.call(Methods.OPEN_INDEX, new OpenIndexParams("b"));
            await(backend.call(Methods.OPEN_INDEX, new OpenIndexParams("c")));
        }

        assertThat(paths).containsExactly("a", "b", "c");
        assertThat(threads).hasSize(3).allSatisfy(thread -> {
            assertThat(thread.getName()).isEqualTo("leia-backend");
            assertThat(thread).isSameAs(threads.getFirst());
            assertThat(thread.getContextClassLoader()).isSameAs(JarChain.classLoader());
        });
    }

    @Test
    void failsWithTheHandlersRpcException() throws Exception {
        RpcException failure = new RpcException(ErrorCode.NO_INDEX_OPEN, "No index is open");

        try (LeiaBackend backend = backendWithVersion(params -> {
            throw failure;
        })) {
            assertThat(failure(backend.call(Methods.VERSION, new VersionParams())))
                    .isSameAs(failure);
        }
    }

    @Test
    void failsWithAnInternalErrorForAnyOtherRuntimeException() throws Exception {
        try (LeiaBackend backend = backendWithVersion(params -> {
            throw new IllegalStateException("boom");
        })) {
            assertThat(failure(backend.call(Methods.VERSION, new VersionParams())))
                    .isInstanceOfSatisfying(
                            RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR))
                    .hasMessageContaining("boom");
        }
    }

    @Test
    void failsWithAnErrorInsteadOfWaitingForever() throws Exception {
        LinkageError error = new LinkageError("bad jar");

        try (LeiaBackend backend = backendWithVersion(params -> {
            throw error;
        })) {
            assertThat(failure(backend.call(Methods.VERSION, new VersionParams())))
                    .isSameAs(error);
            // The thread survives it.
            assertThat(failure(backend.call(Methods.VERSION, new VersionParams())))
                    .isSameAs(error);
        }
    }

    @Test
    void rejectsNullParams() throws Exception {
        try (LeiaBackend backend = backendWithVersion(params -> null)) {
            assertThatThrownBy(() -> backend.call(Methods.VERSION, null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void closesOnceAndFailsTheCallsAfterwards() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        LeiaBackend backend = new LeiaBackend(TestHandlers.with(), closed::incrementAndGet);

        backend.close();
        backend.close();

        assertThat(closed).hasValue(1);
        assertThat(failure(backend.call(Methods.VERSION, new VersionParams())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startsWithTheServersMethods() throws Exception {
        Path index = writeIndex(tmp.resolve("index"), 3);

        try (LeiaBackend backend = LeiaBackend.start(Optional.empty(), List.of())) {
            VersionResult version = await(backend.call(Methods.VERSION, new VersionParams()));
            assertThat(version.java().version()).isEqualTo(Runtime.version().toString());

            await(backend.call(Methods.OPEN_INDEX, new OpenIndexParams(index.toString())));
            assertThat(await(backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams()))
                            .numDocs())
                    .isEqualTo(3);

            long jobId = await(backend.call(Methods.START_FIELD_TERM_SCAN, new StartFieldTermScanParams("id", 10)))
                    .jobId();
            assertThat(pollFieldTermScan(backend, jobId).uniqueTerms()).isEqualTo(3);
        }
    }

    @Test
    void failsToStartWithAPathThatIsNoJar() throws Exception {
        Path notAJar = Files.writeString(tmp.resolve("notes.txt"), "no jar");

        assertThatThrownBy(() -> LeiaBackend.start(Optional.empty(), List.of(notAJar)))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_JAR));
    }

    private static LeiaBackend backendWithVersion(TestHandlers.Body<VersionParams, VersionResult> body) {
        return new LeiaBackend(TestHandlers.with(TestHandlers.handler(Methods.VERSION, body)), () -> {});
    }

    private static FieldTerms pollFieldTermScan(LeiaBackend backend, long jobId) throws Exception {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            GetFieldTermScanResult poll = await(backend.call(Methods.GET_FIELD_TERM_SCAN, new JobParams(jobId)));
            if (poll.terms().isPresent()) {
                return poll.terms().get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("The scan did not finish within " + TIMEOUT);
    }

    private static <R> R await(CompletableFuture<R> future) throws Exception {
        return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** What {@code future} fails with, as the UI sees it. */
    private static Throwable failure(CompletableFuture<?> future) throws Exception {
        Throwable failure = future.handle((result, e) -> e).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(failure).as("the call must fail").isNotNull();
        return failure;
    }

    private static Path writeIndex(Path dir, int docs) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (int i = 1; i <= docs; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
                writer.addDocument(doc);
            }
            writer.commit();
        }
        return dir;
    }
}
