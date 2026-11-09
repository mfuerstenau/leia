package io.github.mfuerstenau.leia.client.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The recently opened index paths (ADR 0020): newest first, at most {@link #MAX_PATHS}, kept in a
 * file of the platform's state directory, one path per line.
 */
public final class History {

    /** How many paths are kept. */
    public static final int MAX_PATHS = 10;

    private final List<String> paths;

    /** Where the paths are kept; empty keeps them in memory only. */
    private final @Nullable Path file;

    private History(List<String> paths, @Nullable Path file) {
        this.paths = paths;
        this.file = file;
    }

    /** A history that is not kept in a file. */
    public static History inMemory() {
        return new History(new ArrayList<>(), null);
    }

    /** Reads the history from {@code file}, and keeps it there. A missing or unreadable file is an empty history. */
    public static History load(Path file) {
        return load(file, file);
    }

    /**
     * Reads the history from {@code read}, which may be another state folder than the one it is
     * kept in (ADR 0122), and writes it to {@code write}.
     */
    public static History load(Path read, Path write) {
        List<String> paths = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(read, StandardCharsets.UTF_8)) {
                if (!line.isEmpty() && paths.size() < MAX_PATHS && !paths.contains(line)) {
                    paths.add(line);
                }
            }
        } catch (IOException _) {
            paths.clear();
        }
        return new History(paths, write);
    }

    /** Newest first. */
    public List<String> paths() {
        return List.copyOf(paths);
    }

    /**
     * Puts {@code path} first and writes the file. A path with a line break can't be kept in it and
     * is left out. A failed write is ignored: the history is a convenience, and the TUI has nowhere
     * to report it.
     */
    public void add(String path) {
        if (path.isEmpty() || path.contains("\n") || path.contains("\r")) {
            return;
        }
        paths.remove(path);
        paths.addFirst(path);
        while (paths.size() > MAX_PATHS) {
            paths.removeLast();
        }
        if (file != null) {
            write(file);
        }
    }

    /** Writes the whole file through a temporary one, so that a reader never sees half of it. */
    private void write(Path file) {
        Path temporary = file.resolveSibling(
                file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.writeString(temporary, String.join("\n", paths) + "\n", StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException _) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException _) {
                // Nothing else to do: the history is a convenience.
            }
        }
    }
}
