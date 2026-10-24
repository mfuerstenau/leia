package io.github.mfuerstenau.leia.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.runtime.RuntimeIndex.Skipped;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IndexPartsTest {

    private static final Path A = Path.of("a");
    private static final Path B = Path.of("b");
    private static final Path C = Path.of("c");
    private static final Path D = Path.of("d");

    private final List<String> closed = new ArrayList<>();

    /** Closes a part by noting its name. */
    private List<Closeable> closeables(String part) {
        return List.of(() -> closed.add(part));
    }

    /** The fields of {@code b} conflict with those of {@code a}. */
    private static Optional<String> conflict(List<String> parts) {
        return parts.containsAll(List.of("a", "b")) ? Optional.of("rank") : Optional.empty();
    }

    private IndexParts.Opened<String> compatible(List<Path> dirs, IndexParts.Opened<String> opened) throws IOException {
        return IndexParts.compatible(dirs, opened, Path::of, IndexPartsTest::conflict, this::closeables);
    }

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

    @Test
    void partsWithoutAConflictStayAsTheyAre() throws IOException {
        IndexParts.Opened<String> opened = new IndexParts.Opened<>(List.of("a", "c"), List.of());

        assertThat(compatible(List.of(A, C), opened)).isSameAs(opened);
        assertThat(closed).isEmpty();
    }

    @Test
    void aConflictingPartIsSkippedAndClosed() throws IOException {
        IndexParts.Opened<String> opened =
                compatible(List.of(A, B, C), new IndexParts.Opened<>(List.of("a", "b", "c"), List.of()));

        assertThat(opened.parts()).containsExactly("a", "c");
        assertThat(opened.skipped())
                .containsExactly(new Skipped(B, "Its fields conflict with those of the indexes before it: rank"));
        assertThat(closed).containsExactly("b");
    }

    @Test
    void theSkippedStayInTheOrderOfTheDirectories() throws IOException {
        IndexParts.Opened<String> opened = compatible(
                List.of(A, B, C, D),
                new IndexParts.Opened<>(List.of("a", "b", "d"), List.of(new Skipped(C, "c is broken"))));

        assertThat(opened.parts()).containsExactly("a", "d");
        assertThat(opened.skipped()).extracting(Skipped::path).containsExactly(B, C);
    }

    @Test
    void aFailingConflictClosesEveryPart() {
        IOException failure = new IOException("unreadable");

        assertThatThrownBy(() -> IndexParts.compatible(
                        List.of(A, C),
                        new IndexParts.Opened<>(List.of("a", "c"), List.of()),
                        Path::of,
                        _ -> {
                            throw failure;
                        },
                        this::closeables))
                .isSameAs(failure);
        assertThat(closed).containsExactly("a", "c");
    }
}
