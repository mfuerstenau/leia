package io.github.mfuerstenau.leia.lucene5;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unmapping only with {@code sun.misc.unsafe.memory.access=allow} (ADR 0047). */
class DirectoriesTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(
            value = DirectoryKind.class,
            names = {"FS", "MMAP"})
    void mappedDirectoriesDoNotUnmapWithoutTheProperty(DirectoryKind kind) throws IOException {
        // The test JVM doesn't get the scripts' -D.
        assertThat(System.getProperty("sun.misc.unsafe.memory.access")).isNull();

        try (Directory directory = Directories.open(dir, kind)) {
            assertThat(directory)
                    .isInstanceOfSatisfying(
                            MMapDirectory.class,
                            mmap -> assertThat(mmap.getUseUnmap()).isFalse());
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"warn", "deny", "debug", ""})
    void unmapsOnlyWhenAllowed(String access) {
        assertThat(Directories.unmap(access)).isFalse();
    }

    @Test
    void unmapsWhenAllowed() {
        assertThat(MMapDirectory.UNMAP_SUPPORTED).isTrue();
        assertThat(Directories.unmap("allow")).isTrue();
    }
}
