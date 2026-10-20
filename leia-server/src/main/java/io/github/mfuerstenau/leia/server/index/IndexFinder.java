package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.server.RpcException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Finds the index directories of a path (ADR 0021): the path itself if it holds an index, or else
 * every directory below it that does. It only lists directories; it reads no index file. The server
 * routes each directory found to the runtime that reads it (ADR 0045, ADR 0071).
 */
public final class IndexFinder {

    private static final Logger LOG = Logger.getLogger(IndexFinder.class.getName());

    /** The walk below a path stops after listing this many directories. */
    static final int MAX_DIRECTORIES = 10_000;

    private static final String SEGMENTS_PREFIX = "segments_";

    private final Path root;
    private final int maxDirectories;
    private int listed;

    private IndexFinder(Path root, int maxDirectories) {
        this.root = root;
        this.maxDirectories = maxDirectories;
    }

    /**
     * {@code path} made absolute and normalized.
     *
     * @throws RpcException if it doesn't exist or isn't a directory
     */
    public static Path root(Path path) throws RpcException {
        Path dir = path.toAbsolutePath().normalize();
        if (!Files.exists(dir)) {
            throw new RpcException(ErrorCode.INDEX_NOT_FOUND, "No such file or directory: " + dir);
        }
        if (!Files.isDirectory(dir)) {
            throw new RpcException(ErrorCode.NOT_AN_INDEX, "Not a directory: " + dir);
        }
        return dir;
    }

    /**
     * The index directories of {@code dir}, an existing directory: {@code dir} itself if it holds an
     * index, or else every directory below it that does, depth-first in name order. Nothing below a
     * directory that holds an index is looked at, symbolic links are not followed, and a directory
     * below {@code dir} that can't be listed is passed over.
     *
     * @throws RpcException if {@code dir} can't be listed, no index is found, or the walk lists
     *     more than {@value #MAX_DIRECTORIES} directories
     */
    public static List<Path> find(Path dir) throws RpcException {
        return find(dir, MAX_DIRECTORIES);
    }

    static List<Path> find(Path dir, int maxDirectories) throws RpcException {
        IndexFinder finder = new IndexFinder(dir, maxDirectories);
        List<Path> found = new ArrayList<>();
        finder.walk(dir, found);
        if (found.isEmpty()) {
            throw new RpcException(ErrorCode.NOT_AN_INDEX, "No Lucene index in or below " + dir);
        }
        return List.copyOf(found);
    }

    /** Adds {@code dir} if it holds an index, or else the indexes below it, in name order. */
    private void walk(Path dir, List<Path> found) throws RpcException {
        if (++listed > maxDirectories) {
            throw new RpcException(
                    ErrorCode.NOT_AN_INDEX,
                    "More than " + maxDirectories + " directories below " + root
                            + "; open a path closer to the indexes");
        }
        List<Path> subdirectories = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                // Not pending_segments_N, which a writer has not finished.
                if (name.startsWith(SEGMENTS_PREFIX) && Files.isRegularFile(entry)) {
                    found.add(dir);
                    return;
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    subdirectories.add(entry);
                }
            }
        } catch (IOException e) {
            if (dir.equals(root)) {
                throw e instanceof AccessDeniedException
                        ? new RpcException(ErrorCode.INDEX_NOT_READABLE, "Permission denied: " + e.getMessage(), e)
                        : new RpcException(ErrorCode.INDEX_NOT_READABLE, "Cannot list " + dir + ": " + e, e);
            }
            LOG.log(Level.FINE, e, () -> "Cannot list " + dir);
            return;
        }
        subdirectories.sort(null);
        for (Path subdirectory : subdirectories) {
            walk(subdirectory, found);
        }
    }
}
