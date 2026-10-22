package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.testFile;
import static io.github.mfuerstenau.leia.server.RuntimeLoadBackendTest.copyFolder;
import static io.github.mfuerstenau.leia.server.RuntimeLoadBackendTest.state;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesParams;
import io.github.mfuerstenau.leia.protocol.jars.LoadRuntimesParams;
import io.github.mfuerstenau.leia.protocol.jars.LoadRuntimesResult;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeFolder;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lucene 10's folder, downloaded once jars are loaded: it can't become the jar chain's first link
 * then, so leia has to restart to load it, while an old runtime still loads (ADR 0080). In a JVM of
 * its own, from runtimeLoadTest.
 */
@Tag("runtimeLoad")
class RuntimeRestartBackendTest {

    @TempDir
    Path tmp;

    @Test
    void needsARestartForLucene10OnceJarsAreLoaded() throws Exception {
        Path built = testFile("leia.test.runtimes");
        Path runtimes = Files.createDirectories(tmp.resolve("runtimes"));
        Path jar = tmp.resolve("empty.jar");
        new JarOutputStream(Files.newOutputStream(jar)).close();
        try (RealBackend backend = RealBackend.start(Optional.of(runtimes), List.of(jar))) {
            copyFolder(built, runtimes, "lucene10");
            copyFolder(built, runtimes, "lucene7");

            LoadRuntimesResult result = backend.call(Methods.LOAD_RUNTIMES, new LoadRuntimesParams());

            assertThat(result.loaded()).containsExactly("lucene7");
            assertThat(result.runtimes().getFirst())
                    .isEqualTo(new RuntimeFolder(
                            "lucene10", List.of(9, 10), RuntimeState.RESTART, Optional.empty(), Optional.empty()));
            assertThat(state(result, "lucene7")).isEqualTo(RuntimeState.LOADED);
            // Asked again, it stays so.
            assertThat(backend.call(Methods.LOAD_RUNTIMES, new LoadRuntimesParams())
                            .loaded())
                    .isEmpty();
            assertThat(backend.call(Methods.GET_RUNTIMES, new GetRuntimesParams())
                            .runtimes()
                            .getFirst()
                            .state())
                    .isEqualTo(RuntimeState.RESTART);
        }
    }
}
