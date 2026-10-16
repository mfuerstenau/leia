package io.github.mfuerstenau.leia.lucene9;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 9.12 maps files as memory segments, through the classes of its jar's {@code META-INF/versions},
 * and needs no switch to unmap (ADR 0049).
 */
class DirectoriesTest {

    private static Path testIndex() {
        return Path.of(System.getProperty("leia.test.index.lucene8")).toAbsolutePath();
    }

    @ParameterizedTest
    @EnumSource(
            value = DirectoryKind.class,
            names = {"FS", "MMAP"})
    void mapsAsMemorySegments(DirectoryKind kind) throws IOException {
        try (Directory directory = Directories.open(testIndex(), kind)) {
            assertThat(directory).isInstanceOf(MMapDirectory.class);
            String segments = List.of(directory.listAll()).stream()
                    .filter(name -> name.startsWith("segments_"))
                    .findFirst()
                    .orElseThrow();
            try (IndexInput input = directory.openInput(segments, IOContext.READONCE)) {
                assertThat(input.getClass().getName()).contains("MemorySegmentIndexInput");
            }
        }
    }

    @Test
    void opensNio() throws IOException {
        try (Directory directory = Directories.open(testIndex(), DirectoryKind.NIO)) {
            assertThat(directory).isInstanceOf(NIOFSDirectory.class);
        }
    }

    @Test
    @SuppressWarnings("removal")
    void hasNoUnmapSwitchForADirectory() throws IOException {
        try (Directory directory = Directories.open(testIndex(), DirectoryKind.MMAP)) {
            MMapDirectory mmap = (MMapDirectory) directory;
            assertThat(mmap.getUseUnmap()).isTrue();
            // It throws for any value but the global one.
            assertThatThrownBy(() -> mmap.setUseUnmap(false))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("no longer possible");
        }
    }
}
