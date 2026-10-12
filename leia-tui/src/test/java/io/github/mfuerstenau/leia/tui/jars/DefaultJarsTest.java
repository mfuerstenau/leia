package io.github.mfuerstenau.leia.tui.jars;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultJarsTest {

    @TempDir
    Path tmp;

    @Test
    void loadsAFolderWithAJar() throws Exception {
        Files.createFile(tmp.resolve("analysis.JAR"));

        DefaultJars jars = DefaultJars.find(Optional.of(tmp), false);

        assertThat(jars.state()).isEqualTo(DefaultJars.State.LOADED);
        assertThat(jars.loadedDir()).hasValue(tmp);
    }

    @Test
    void skipsTheFolderWhenAsked() throws Exception {
        Files.createFile(tmp.resolve("analysis.jar"));

        DefaultJars jars = DefaultJars.find(Optional.of(tmp), true);

        assertThat(jars.state()).isEqualTo(DefaultJars.State.SKIPPED);
        assertThat(jars.loadedDir()).isEmpty();
    }

    @Test
    void findsNoJarsInAMissingFolderOrBelowIt() throws Exception {
        Files.createFile(Files.createDirectory(tmp.resolve("sub")).resolve("analysis.jar"));
        Files.createDirectory(tmp.resolve("dir.jar"));

        assertThat(DefaultJars.find(Optional.of(tmp), false).state()).isEqualTo(DefaultJars.State.NO_JARS);
        assertThat(DefaultJars.find(Optional.of(tmp.resolve("missing")), false).state())
                .isEqualTo(DefaultJars.State.NO_JARS);
        assertThat(DefaultJars.find(Optional.empty(), false).loadedDir()).isEmpty();
    }

    @Test
    void aJarNameEndsWithJarInAnyCase() {
        assertThat(DefaultJars.isJarName("analysis.jar")).isTrue();
        assertThat(DefaultJars.isJarName("Analysis.JAR")).isTrue();
        assertThat(DefaultJars.isJarName("analysis.jar.txt")).isFalse();
        assertThat(DefaultJars.none().dir()).isEmpty();
    }
}
