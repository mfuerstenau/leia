package io.github.mfuerstenau.leia.tui.jars;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeJarsTest {

    @TempDir
    Path tmp;

    @Test
    void loadsAFolderWithAJar() throws Exception {
        Files.createFile(tmp.resolve("leia-lucene3.jar"));

        RuntimeJars jars = RuntimeJars.find(Optional.of(tmp));

        assertThat(jars.state()).isEqualTo(RuntimeJars.State.LOADED);
        assertThat(jars.loadedDir()).hasValue(tmp);
    }

    @Test
    void findsNoJarsInAMissingOrEmptyFolderOrWithoutLeiaJar() throws Exception {
        assertThat(RuntimeJars.find(Optional.of(tmp)).state()).isEqualTo(RuntimeJars.State.NO_JARS);
        assertThat(RuntimeJars.find(Optional.of(tmp.resolve("missing"))).loadedDir())
                .isEmpty();
        assertThat(RuntimeJars.find(Optional.empty())).isEqualTo(RuntimeJars.none());
    }
}
