package io.github.mfuerstenau.leia.server.jars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.runtime.Extensions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The paths that {@link JarChain} takes, where nothing gets loaded: loading changes Lucene's global
 * lists, so that is left to {@code JarsBackendTest} in the {@code jarsTest} JVM.
 */
class JarChainTest {

    @TempDir
    Path tmp;

    @Test
    void aPathMustBeAJarOrADirectoryWithJars() throws IOException {
        Path text = Files.writeString(tmp.resolve("notes.txt"), "not a jar");
        Path empty = Files.createDirectory(tmp.resolve("empty"));
        Path missing = tmp.resolve("missing.jar");

        assertThatThrownBy(() -> JarChain.load(List.of(text)))
                .isInstanceOf(JarException.class)
                .hasMessage("not a jar: " + text);
        assertThatThrownBy(() -> JarChain.load(List.of(empty)))
                .isInstanceOf(JarException.class)
                .hasMessage("no jars in " + empty);
        assertThatThrownBy(() -> JarChain.add(List.of(missing)))
                .isInstanceOf(JarException.class)
                .hasMessage("no such jar or directory: " + missing);
        assertThat(JarChain.jars()).isEmpty();
    }

    @Test
    void noPathsLoadNothing() throws Exception {
        ClassLoader before = JarChain.classLoader();

        assertThat(JarChain.load(List.of())).isEmpty();
        assertThat(JarChain.add(List.of())).isEqualTo(new AddedJars(List.of(), List.of(), Extensions.Added.NONE));
        assertThat(JarChain.classLoader()).isSameAs(before);
    }
}
