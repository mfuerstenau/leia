package io.github.mfuerstenau.leia.server.runtime;

import java.io.Closeable;
import java.nio.file.Path;

/**
 * The indexes of an opened path (ADR 0071): read by a runtime with a reader, or only their
 * directories, whose commits and check the runtimes read from the path.
 */
public sealed interface OpenIndex extends Closeable permits OpenRuntime, OpenDirectories {

    /** The absolute, normalized path that was opened. */
    Path path();
}
