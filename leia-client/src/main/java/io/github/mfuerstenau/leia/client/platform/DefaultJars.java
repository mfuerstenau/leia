package io.github.mfuerstenau.leia.client.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The default folder of jars, which are loaded at every start after those of {@code --jar}, and
 * whether they were (ADR 0024).
 *
 * @param dir the folder; null if the platform doesn't say where it is
 */
public record DefaultJars(@Nullable Path dir, State state) {

    public enum State {
        /** Loaded, as one more jar path after those of {@code --jar}. */
        LOADED,
        /** Missing, or without a jar directly in it. */
        NO_JARS,
        /** Left out by {@code --no-default-jars}. */
        SKIPPED
    }

    /** No default folder: the platform doesn't say where it is. */
    public static DefaultJars none() {
        return new DefaultJars(null, State.NO_JARS);
    }

    /** Looks into {@code dir}, if not null: its jars are loaded if it has any, unless {@code skip}. */
    public static DefaultJars find(@Nullable Path dir, boolean skip) {
        State state = State.SKIPPED;
        if (!skip) {
            state = dir != null && hasJars(dir) ? State.LOADED : State.NO_JARS;
        }
        return new DefaultJars(dir, state);
    }

    /** The folder to load, if its jars are loaded. */
    public Optional<Path> loadedDir() {
        return Optional.ofNullable(dir).filter(_ -> state == State.LOADED);
    }

    /** Whether a file name is a jar's, as the server sees it: it ends with {@code .jar}, in any case. */
    public static boolean isJarName(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    /** Whether {@code dir} has a jar directly in it. */
    public static boolean hasJars(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.anyMatch(entry ->
                    Files.isRegularFile(entry) && isJarName(entry.getFileName().toString()));
        } catch (IOException _) {
            return false;
        }
    }
}
