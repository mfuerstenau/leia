package io.github.mfuerstenau.leia.lucene10;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.IndexReadException;
import io.github.mfuerstenau.leia.runtime.IndexReadException.Reason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lucene 10 tells leia why it can't read an index, in leia-core's words (ADR 0070). */
class Lucene10FailuresTest {

    @Test
    void opensATruncatedIndexAsCorrupt(@TempDir Path dir) throws IOException {
        Path source = Path.of(System.getProperty("leia.test.index.lucene9"));
        try (Stream<Path> files = Files.list(source)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith("segments")) {
                    Files.copy(file, dir.resolve(name));
                } else if (!name.equals("write.lock")) {
                    // Every other file is there, but empty.
                    Files.createFile(dir.resolve(name));
                }
            }
        }

        assertThatThrownBy(() -> new Lucene10Runtime()
                        .open(List.of(dir), DirectoryKind.FS)
                        .close())
                .isInstanceOfSatisfying(
                        IndexReadException.class, e -> assertThat(e.reason()).isEqualTo(Reason.CORRUPT))
                .hasMessageStartingWith("Corrupt index");
        assertThatThrownBy(() -> new Lucene10Runtime()
                        .commits()
                        .orElseThrow()
                        .commit(dir, DirectoryKind.FS, Long.parseLong(segmentsGeneration(dir), Character.MAX_RADIX)))
                .isInstanceOfSatisfying(
                        IndexReadException.class, e -> assertThat(e.reason()).isEqualTo(Reason.CORRUPT));
    }

    private static String segmentsGeneration(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.startsWith("segments_"))
                    .findFirst()
                    .orElseThrow()
                    .substring("segments_".length());
        }
    }
}
