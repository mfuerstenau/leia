package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import java.nio.file.Path;
import java.util.List;

/**
 * The index directories of a path opened without a reader (ADR 0021, ADR 0071). It holds no Lucene
 * object: the runtimes read a directory's commits and check it from its path.
 *
 * @param path the absolute, normalized path that was opened
 * @param directories the index directories found at or below {@code path}, in order
 * @param kind the {@code Directory} the runtimes read them through
 */
public record OpenDirectories(Path path, List<Path> directories, DirectoryKind kind) implements OpenIndex {

    public OpenDirectories {
        directories = List.copyOf(directories);
    }

    /** Nothing to close: no reader or directory is open. */
    @Override
    public void close() {}
}
