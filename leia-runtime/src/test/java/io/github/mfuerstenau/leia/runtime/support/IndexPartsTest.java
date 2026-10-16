package io.github.mfuerstenau.leia.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.runtime.RuntimeIndex.Skipped;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndexPartsTest {

    private static final Path A = Path.of("a");
    private static final Path B = Path.of("b");
    private static final Path C = Path.of("c");

    /** Opens a directory as its name, and fails on {@code b} and {@code c}. */
    private static String open(Path dir) throws IOException {
        return switch (dir.toString()) {
            case "b" -> throw new IOException("b is broken");
            case "c" -> throw new IllegalStateException();
            default -> dir.toString();
        };
    }

    @Test
    void noDirectoryIsRefused() {
        assertThatThrownBy(() -> IndexParts.open(List.of(), IndexPartsTest::open))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSingleDirectoryFailsAsItFailed() {
        assertThatThrownBy(() -> IndexParts.open(List.of(B), IndexPartsTest::open))
                .isInstanceOf(IOException.class)
                .hasMessage("b is broken");
        assertThatThrownBy(() -> IndexParts.open(List.of(C), IndexPartsTest::open))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failingDirectoriesAreSkipped() throws IOException {
        IndexParts.Opened<String> opened = IndexParts.open(List.of(B, A, C), IndexPartsTest::open);

        assertThat(opened.parts()).containsExactly("a");
        assertThat(opened.skipped())
                .containsExactly(new Skipped(B, "b is broken"), new Skipped(C, "IllegalStateException"));
    }

    @Test
    void theFirstFailureIsThrownIfNoneOpens() {
        assertThatThrownBy(() -> IndexParts.open(List.of(C, B), IndexPartsTest::open))
                .isInstanceOf(IOException.class)
                .hasMessage("c: IllegalStateException")
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
