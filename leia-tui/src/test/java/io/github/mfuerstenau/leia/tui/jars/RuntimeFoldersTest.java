package io.github.mfuerstenau.leia.tui.jars;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeFoldersTest {

    @TempDir
    Path tmp;

    @Test
    void existsOnlyAsAFolder() throws Exception {
        assertThat(new RuntimeFolders(Optional.of(tmp)).exists()).isTrue();
        assertThat(new RuntimeFolders(Optional.of(tmp.resolve("missing"))).exists())
                .isFalse();
        assertThat(Files.isDirectory(tmp)).isTrue();
        assertThat(RuntimeFolders.none().exists()).isFalse();
    }

    @Test
    void existsWhenAnyOfTheFoldersDoesAndNamesTheRuntimeFolderOfAJarInAny() {
        Path second = tmp.resolve("second");
        RuntimeFolders folders = new RuntimeFolders(List.of(tmp.resolve("missing"), second));

        assertThat(folders.exists()).isFalse();
        assertThat(folders.dir()).hasValue(tmp.resolve("missing"));
        assertThat(folders.folderOf(second.resolve("lucene9").resolve("x.jar"))).hasValue("runtimes/lucene9");
        assertThat(folders.folderOf(tmp.resolve("lucene9").resolve("x.jar"))).isEmpty();
        assertThat(new RuntimeFolders(List.of(tmp.resolve("missing"), tmp)).exists())
                .isTrue();
    }

    @Test
    void namesTheRuntimeFolderOfAJar() {
        RuntimeFolders folders = new RuntimeFolders(Optional.of(tmp));

        assertThat(folders.folderOf(tmp.resolve("lucene10").resolve("lucene-core-10.5.2.jar")))
                .hasValue("runtimes/lucene10");
        assertThat(folders.folderOf(tmp.resolve("leia-lucene10.jar"))).isEmpty();
        assertThat(folders.folderOf(Path.of("/elsewhere/lucene10/x.jar"))).isEmpty();
        assertThat(RuntimeFolders.none().folderOf(tmp.resolve("lucene10").resolve("x.jar")))
                .isEmpty();
    }
}
