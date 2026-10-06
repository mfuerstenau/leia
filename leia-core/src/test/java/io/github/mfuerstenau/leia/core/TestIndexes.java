package io.github.mfuerstenau.leia.core;

import java.nio.file.Path;

/** Test indexes that the build provides. */
final class TestIndexes {

    private TestIndexes() {}

    /** A test index written by an older Lucene version (see build.gradle.kts). */
    static Path legacyIndex(String name) {
        String dir = System.getProperty("leia.test.index." + name);
        if (dir == null) {
            throw new IllegalStateException("System property leia.test.index." + name + " is not set");
        }
        return Path.of(dir).toAbsolutePath();
    }
}
