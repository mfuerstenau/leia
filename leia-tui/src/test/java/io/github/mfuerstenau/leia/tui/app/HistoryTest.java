package io.github.mfuerstenau.leia.tui.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryTest {

    @TempDir
    Path tmp;

    @Test
    void addsTheNewestFirstWithoutDuplicates() {
        History history = History.inMemory();
        history.add("/a");
        history.add("/b");
        history.add("/a");

        assertThat(history.paths()).containsExactly("/a", "/b");
    }

    @Test
    void keepsAtMostTenPaths() {
        History history = History.inMemory();
        for (int i = 0; i < 12; i++) {
            history.add("/" + i);
        }

        assertThat(history.paths()).hasSize(History.MAX_PATHS);
        assertThat(history.paths().getFirst()).isEqualTo("/11");
        assertThat(history.paths().get(9)).isEqualTo("/2");
    }

    @Test
    void leavesOutPathsWithLineBreaks() {
        History history = History.inMemory();
        history.add("/a\nb");
        history.add("/a\rb");
        history.add("");

        assertThat(history.paths()).isEmpty();
    }

    @Test
    void writesTheFileAndReadsItBack() throws IOException {
        Path file = tmp.resolve("state").resolve("leia").resolve("history");
        History history = History.load(file);
        assertThat(history.paths()).isEmpty();

        history.add("/a");
        history.add("C:\\b c");

        assertThat(Files.readString(file)).isEqualTo("C:\\b c\n/a\n");
        assertThat(History.load(file).paths()).containsExactly("C:\\b c", "/a");
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    void readsFromOneStateFolderAndWritesToAnother() throws IOException {
        Path old = tmp.resolve("old/history");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "/a\n");
        Path moved = tmp.resolve("new/history");

        History history = History.load(old, moved);
        assertThat(history.paths()).containsExactly("/a");
        history.add("/b");

        assertThat(Files.readString(moved)).isEqualTo("/b\n/a\n");
        assertThat(Files.readString(old)).isEqualTo("/a\n");
    }

    @Test
    void readsAFileThatWasEditedByHand() throws IOException {
        Path file = tmp.resolve("history");
        List<String> lines = new ArrayList<>(List.of("/a", "", "/b", "/a"));
        IntStream.range(0, 20).forEach(i -> lines.add("/" + i));
        Files.writeString(file, String.join("\r\n", lines));

        History history = History.load(file);

        assertThat(history.paths().subList(0, 3)).containsExactly("/a", "/b", "/0");
        assertThat(history.paths()).hasSize(History.MAX_PATHS);
    }

    @Test
    void aFailedWriteKeepsThePaths() throws IOException {
        // A file where the directory should be.
        Files.writeString(tmp.resolve("leia"), "");
        History history = History.load(tmp.resolve("leia").resolve("history"));

        history.add("/a");

        assertThat(history.paths()).containsExactly("/a");
    }
}
