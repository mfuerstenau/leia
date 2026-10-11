package io.github.mfuerstenau.leia.tui;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import io.github.mfuerstenau.leia.server.RpcException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The UI reaches the server's methods in-process, through {@link LeiaBackend} (ADR 0034). */
class LeiaBackendWiringTest {

    @TempDir
    Path tmp;

    private LeiaBackend backend;

    @BeforeEach
    void start() throws RpcException {
        backend = LeiaBackend.start(List.of());
    }

    @AfterEach
    void close() {
        backend.close();
    }

    @Test
    void answersVersion() throws Exception {
        assertThat(backend.call(Methods.VERSION, new VersionParams())
                        .get(10, TimeUnit.SECONDS)
                        .java()
                        .version())
                .isEqualTo(Runtime.version().toString());
    }

    @Test
    void reportsTheErrorCodeOfAPathThatDoesNotExist() throws Exception {
        assertThat(errorCode(backend.call(
                        Methods.OPEN_INDEX,
                        new OpenIndexParams(tmp.resolve("missing").toString()))))
                .isEqualTo(ErrorCode.INDEX_NOT_FOUND);
    }

    @Test
    void reportsTheErrorCodeOfADirectoryWithoutAnIndex() throws Exception {
        Path empty = Files.createDirectory(tmp.resolve("empty"));

        assertThat(errorCode(backend.call(Methods.OPEN_INDEX, new OpenIndexParams(empty.toString()))))
                .isEqualTo(ErrorCode.NOT_AN_INDEX);
    }

    private static ErrorCode errorCode(CompletableFuture<?> future) throws Exception {
        Throwable failure = future.handle((result, e) -> e).get(10, TimeUnit.SECONDS);
        assertThat(failure).isInstanceOf(RpcException.class);
        return ((RpcException) failure).code();
    }
}
