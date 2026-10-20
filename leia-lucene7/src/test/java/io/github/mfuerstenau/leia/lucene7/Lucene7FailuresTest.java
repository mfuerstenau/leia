package io.github.mfuerstenau.leia.lucene7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.IndexReadException;
import io.github.mfuerstenau.leia.runtime.IndexReadException.Reason;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 7.7 tells leia why it can't read an index, as leia-core does (ADR 0070). */
class Lucene7FailuresTest {

    private static Optional<Reason> reason(Throwable e) {
        return Lucene7Failures.classify(e).map(IndexReadException::reason);
    }

    @Test
    void classifiesLucenesFailures() {
        assertThat(reason(new CorruptIndexException("checksum failed", "_0.cfs")))
                .contains(Reason.CORRUPT);
        assertThat(reason(new EOFException("read past EOF"))).contains(Reason.CORRUPT);
        assertThat(reason(new IOException("wrapped", new NoSuchFileException("_0.cfs"))))
                .contains(Reason.CORRUPT);
        assertThat(reason(new IndexFormatTooNewException("segments_2", 99, 0, 1)))
                .contains(Reason.UNSUPPORTED_FORMAT);
        // No commit at all, and anything else, leia reports as unreadable.
        assertThat(reason(new IndexNotFoundException("no segments* file"))).isEmpty();
        assertThat(reason(new IOException("disk"))).isEmpty();
    }

    @Test
    void namesTheMissingCodec() {
        IllegalArgumentException custom =
                new IllegalArgumentException("An SPI class of type org.apache.lucene.codecs.Codec"
                        + " with name 'Custom' does not exist.  You need to add the corresponding JAR file");
        IndexReadException missing =
                Lucene7Failures.classify(new IllegalStateException(custom)).orElseThrow();
        assertThat(missing.reason()).isEqualTo(Reason.MISSING_CODEC);
        assertThat(missing).hasMessage("the index needs the codec 'Custom', which isn't loaded");

        IndexReadException lucenes = Lucene7Failures.classify(
                        new IllegalArgumentException(
                                "An SPI class of type org.apache.lucene.codecs.PostingsFormat with name 'Lucene99' does not exist"))
                .orElseThrow();
        assertThat(lucenes.reason()).isEqualTo(Reason.UNSUPPORTED_FORMAT);
        assertThat(lucenes)
                .hasMessageEndingWith("has no postings format 'Lucene99'; the index needs another Lucene version");
    }

    @Test
    void opensATruncatedIndexAsCorrupt(@TempDir Path dir) throws IOException {
        Path source = Path.of(System.getProperty("leia.test.index.lucene7"));
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

        assertThatThrownBy(() -> new Lucene7Runtime()
                        .open(List.of(dir), DirectoryKind.FS)
                        .close())
                .isInstanceOfSatisfying(
                        IndexReadException.class, e -> assertThat(e.reason()).isEqualTo(Reason.CORRUPT))
                .hasMessageStartingWith("Corrupt index: ");
    }
}
