package io.github.mfuerstenau.leia.tui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The default folder of jars, which are loaded at every start after those of {@code --jar}, and
 * whether they were (ADR 0024).
 *
 * @param dir the folder; empty if the platform doesn't say where it is
 */
record DefaultJars(Optional<Path> dir, State state) {

    enum State {
        /** Loaded, as one more jar path after those of {@code --jar}. */
        LOADED,
        /** Missing, or without a jar directly in it. */
        NO_JARS,
        /** Left out by {@code --no-default-jars}. */
        SKIPPED
    }

    /** Looks into {@code dir}: its jars are loaded if it has any, unless {@code skip}. */
    static DefaultJars find(Optional<Path> dir, boolean skip) {
        State state =
                skip ? State.SKIPPED : dir.filter(DefaultJars::hasJars).isPresent() ? State.LOADED : State.NO_JARS;
        return new DefaultJars(dir, state);
    }

    /** The folder to load, if its jars are loaded. */
    Optional<Path> loadedDir() {
        return dir.filter(unused -> state == State.LOADED);
    }

    /** Whether {@code dir} has a jar directly in it, as the server sees one: a name ending in .jar, in any case. */
    private static boolean hasJars(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.anyMatch(entry -> Files.isRegularFile(entry)
                    && entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"));
        } catch (IOException e) {
            return false;
        }
    }
}
