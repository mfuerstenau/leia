package io.github.mfuerstenau.leia.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What an opened path gives in every mode (ADR 0021): its index directories and their commits. A
 * {@link ReadOnlyIndex} reads them with a reader too; a {@link DirectoryOnlyIndex} only reads the
 * commits.
 */
public sealed interface OpenedIndex extends Closeable permits ReadOnlyIndex, DirectoryOnlyIndex {

    /** The absolute, normalized path that was opened. */
    Path path();

    /** The index directories of the path, in the order they were found; at least one. */
    List<Path> directories();

    /**
     * The index directories that were found but left out, as {@link ReadOnlyIndex#skipped()} lists
     * them; empty for an index without a reader, which leaves none out.
     */
    default List<Path> skippedDirectories() {
        return List.of();
    }

    /** The {@code Directory} the index directories were opened through. */
    DirectoryImpl directoryImpl();

    /**
     * The commits of the index directory at {@code index} in {@link #directories()}, newest first,
     * read from the directory again: they include commits written after it was opened. The opened
     * one, if any, is {@link CommitInfo#current()}.
     *
     * @throws IllegalArgumentException if there is no index directory at {@code index}
     * @throws IOException if the directory can't be listed
     */
    CommitList commits(int index) throws IOException;

    /**
     * One commit of the index directory at {@code index} in {@link #directories()}, with its segments
     * and files, read from the directory again.
     *
     * @return the commit; empty if the directory has no commit of that generation
     * @throws IllegalArgumentException if there is no index directory at {@code index}
     * @throws IOException if the commit can't be read; a {@link MissingCodecException} if it names a
     *     codec that isn't loaded
     */
    Optional<CommitDetails> commit(int index, long generation) throws IOException;

    /** Whether there is an index directory at {@code index} in {@link #directories()}. */
    default boolean hasDirectory(int index) {
        return index >= 0 && index < directories().size();
    }

    /** @throws IllegalArgumentException if there is no index directory at {@code index} */
    default void checkDirectory(int index) {
        if (!hasDirectory(index)) {
            throw new IllegalArgumentException(
                    "No index " + index + "; there are " + directories().size());
        }
    }
}
