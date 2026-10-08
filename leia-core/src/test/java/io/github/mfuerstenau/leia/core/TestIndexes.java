package io.github.mfuerstenau.leia.core;

import java.nio.file.Path;

/** Test indexes that the build provides. */
final class TestIndexes {

    private TestIndexes() {}

    /** A test index that the build writes: by an older Lucene version, or with the test codec jar (see build.gradle.kts). */
    static Path testIndex(String name) {
        String dir = System.getProperty("leia.test.index." + name);
        if (dir == null) {
            throw new IllegalStateException("System property leia.test.index." + name + " is not set");
        }
        return Path.of(dir).toAbsolutePath();
    }
}
