package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.testFile;
import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesParams;
import io.github.mfuerstenau.leia.protocol.jars.LoadRuntimesParams;
import io.github.mfuerstenau.leia.protocol.jars.LoadRuntimesResult;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeFolder;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runtimes downloaded while leia runs, loaded by {@code loadRuntimes} (ADR 0080): an old runtime in
 * a class loader of its own, and Lucene 10's into the jar chain, which is empty. The folders are
 * loaded once per JVM, so this runs in a JVM of its own, from runtimeLoadTest.
 */
@Tag("runtimeLoad")
class RuntimeLoadBackendTest {

    @TempDir
    Path tmp;

    @Test
    void loadsTheRuntimesCopiedInWhileItRuns() throws Exception {
        Path built = testFile("leia.test.runtimes");
        Path runtimes = Files.createDirectories(tmp.resolve("runtimes"));
        try (RealBackend backend = RealBackend.start(runtimes, List.of())) {
            assertThat(backend.call(Methods.GET_RUNTIMES, new GetRuntimesParams())
                            .runtimes())
                    .allMatch(runtime -> runtime.state() == RuntimeState.MISSING);
            String lucene8 = testIndex("lucene8").toString();
            backend.fails(Methods.OPEN_INDEX, new OpenIndexParams(lucene8), ErrorCode.MISSING_RUNTIME);

            // Nothing new: nothing loaded.
            assertThat(backend.call(Methods.LOAD_RUNTIMES, new LoadRuntimesParams())
                            .loaded())
                    .isEmpty();

            copyFolder(built, runtimes, "lucene9");
            LoadRuntimesResult lucene9 = backend.call(Methods.LOAD_RUNTIMES, new LoadRuntimesParams());

            assertThat(lucene9.loaded()).containsExactly("lucene9");
            assertThat(state(lucene9, "lucene9")).isEqualTo(RuntimeState.LOADED);
            assertThat(state(lucene9, "lucene10")).isEqualTo(RuntimeState.MISSING);
            // Where it came from (ADR 0122).
            assertThat(lucene9.runtimes().stream()
                            .filter(runtime -> runtime.folder().equals("lucene9"))
                            .findFirst()
                            .orElseThrow()
                            .path())
                    .hasValue(runtimes.resolve("lucene9").toString());
            assertThat(backend.call(Methods.OPEN_INDEX, new OpenIndexParams(lucene8))
                            .mode())
                    .isEqualTo(OpenMode.READER);
            backend.call(Methods.CLOSE_INDEX, new CloseIndexParams());

            copyFolder(built, runtimes, "lucene10");
            LoadRuntimesResult lucene10 = backend.call(Methods.LOAD_RUNTIMES, new LoadRuntimesParams());

            assertThat(lucene10.loaded()).containsExactly("lucene10");
            assertThat(lucene10.runtimes().getFirst()).satisfies(runtime -> {
                assertThat(runtime.state()).isEqualTo(RuntimeState.LOADED);
                assertThat(runtime.luceneVersion()).isPresent();
            });
            assertThat(backend.call(
                                    Methods.OPEN_INDEX,
                                    new OpenIndexParams(testIndex("lucene9").toString()))
                            .mode())
                    .isEqualTo(OpenMode.READER);
            // And getRuntimes says the same.
            assertThat(backend.call(Methods.GET_RUNTIMES, new GetRuntimesParams())
                            .runtimes())
                    .isEqualTo(lucene10.runtimes());
        }
    }

    static RuntimeState state(LoadRuntimesResult result, String folder) {
        return result.runtimes().stream()
                .filter(runtime -> runtime.folder().equals(folder))
                .map(RuntimeFolder::state)
                .findFirst()
                .orElseThrow();
    }

    /** Copies the folder {@code name} of {@code from} into {@code to}, as a download would write it. */
    static void copyFolder(Path from, Path to, String name) throws IOException {
        Path target = Files.createDirectories(to.resolve(name));
        try (Stream<Path> files = Files.list(from.resolve(name))) {
            for (Path file : files.toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
    }
}
