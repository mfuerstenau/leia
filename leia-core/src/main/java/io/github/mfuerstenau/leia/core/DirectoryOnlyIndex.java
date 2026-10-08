package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.IOUtils;

/**
 * A path opened without a reader (ADR 0021): only the {@link Directory} of each of its index
 * directories, for indexes that a reader can't open. Only the commits can be read, and none of them
 * is the opened one. Nothing is written: the directories use no locking (AGENTS.md, hard rule 1).
 */
public final class DirectoryOnlyIndex implements OpenedIndex {

    /** No commit has this generation, so {@link CommitInfo#current()} is false for all of them. */
    private static final long NO_GENERATION = 0;

    private final Path path;
    private final DirectoryImpl directoryImpl;
    private final List<Path> dirs;
    private final List<Directory> directories;

    private DirectoryOnlyIndex(Path path, DirectoryImpl directoryImpl, List<Path> dirs, List<Directory> directories) {
        this.path = path;
        this.directoryImpl = directoryImpl;
        this.dirs = List.copyOf(dirs);
        this.directories = List.copyOf(directories);
    }

    /**
     * Opens the directory of every index of {@code path} through {@code directoryImpl}: the index in
     * {@code path}, or else every index below it. No index file is read.
     *
     * @throws IndexOpenException if there is no index at or below {@code path}, or a directory can't
     *     be opened
     */
    public static DirectoryOnlyIndex open(Path path, DirectoryImpl directoryImpl) throws IndexOpenException {
        Path root = IndexFinder.root(path);
        List<Path> dirs = IndexFinder.find(root);
        List<Directory> directories = new ArrayList<>();
        for (Path dir : dirs) {
            try {
                directories.add(directoryImpl.open(dir));
            } catch (IOException e) {
                IOUtils.closeWhileHandlingException(directories);
                throw ReadOnlyIndex.translate(dir, e);
            }
        }
        return new DirectoryOnlyIndex(root, directoryImpl, dirs, directories);
    }

    @Override
    public Path path() {
        return path;
    }

    @Override
    public List<Path> directories() {
        return dirs;
    }

    @Override
    public DirectoryImpl directoryImpl() {
        return directoryImpl;
    }

    @Override
    public CommitList commits(int index) throws IOException {
        checkDirectory(index);
        return CommitReader.commits(directories.get(index), NO_GENERATION);
    }

    @Override
    public Optional<CommitDetails> commit(int index, long generation) throws IOException {
        checkDirectory(index);
        return CommitReader.commit(directories.get(index), generation, NO_GENERATION);
    }

    @Override
    public void close() throws IOException {
        IOUtils.close(directories);
    }
}
