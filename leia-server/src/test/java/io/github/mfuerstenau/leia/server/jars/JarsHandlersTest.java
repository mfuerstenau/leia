package io.github.mfuerstenau.leia.server.jars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsParams;
import io.github.mfuerstenau.leia.protocol.jars.GetJarsParams;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.Routing;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code addJars} and {@code getJars} where nothing gets loaded: loading changes Lucene's global
 * lists, so that is left to the core's {@code jarsTest} and the real-server tests in {@code tui/}.
 */
class JarsHandlersTest {

    @TempDir
    Path tmp;

    private final AddJarsHandler addJars = new AddJarsHandler(new Runtimes(), Routing.DEFAULT);

    @Test
    void noPathsAreInvalidParams() {
        assertThatThrownBy(() -> addJars.handle(new AddJarsParams(List.of())))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMS))
                .hasMessage("Invalid params: no jars to add");
    }

    @Test
    void aPathThatIsNoJarIsAnInvalidJar() throws Exception {
        Path text = Files.writeString(tmp.resolve("notes.txt"), "not a jar");
        Path missing = tmp.resolve("missing.jar");

        assertThatThrownBy(() -> addJars.handle(new AddJarsParams(List.of(text.toString()))))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_JAR))
                .hasMessage("Cannot add the jars: not a jar: " + text);
        assertThatThrownBy(() -> addJars.handle(new AddJarsParams(List.of(missing.toString()))))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_JAR))
                .hasMessage("Cannot add the jars: no such jar or directory: " + missing);
    }

    @Test
    void noJarsAreLoadedWithoutTheCommandLine() {
        assertThat(new GetJarsHandler().handle(new GetJarsParams()).jars()).isEmpty();
    }
}
