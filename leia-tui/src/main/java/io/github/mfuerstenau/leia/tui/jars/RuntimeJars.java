package io.github.mfuerstenau.leia.tui.jars;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The folder {@code runtimes} beside {@code leia.jar}, with a jar for each old Lucene runtime, and
 * whether its jars were loaded (ADR 0044). They are loaded at every start, before those of {@code
 * --jar} and the default folder, whatever {@code --no-default-jars} says.
 *
 * @param dir the folder; empty if leia doesn't run from {@code leia.jar}, as under Gradle
 */
public record RuntimeJars(Optional<Path> dir, State state) {

    public enum State {
        /** Loaded, as the first jar path. */
        LOADED,
        /** Missing, or without a jar directly in it. */
        NO_JARS
    }

    /** No folder: leia doesn't run from {@code leia.jar}. */
    public static RuntimeJars none() {
        return new RuntimeJars(Optional.empty(), State.NO_JARS);
    }

    /** Looks into {@code dir}: its jars are loaded if it has any. */
    public static RuntimeJars find(Optional<Path> dir) {
        return new RuntimeJars(dir, dir.filter(DefaultJars::hasJars).isPresent() ? State.LOADED : State.NO_JARS);
    }

    /** The folder to load, if its jars are loaded. */
    public Optional<Path> loadedDir() {
        return dir.filter(unused -> state == State.LOADED);
    }
}
