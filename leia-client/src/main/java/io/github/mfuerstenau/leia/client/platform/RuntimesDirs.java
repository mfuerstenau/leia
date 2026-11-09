package io.github.mfuerstenau.leia.client.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The runtimes folders, {@code runtimes} beside {@code leia.jar} and in the user's and the system's
 * data folders (ADR 0098, ADR 0122), with a folder for each runtime, such as {@code lucene9} (ADR
 * 0044, ADR 0073). The backend loads them at every start, before the jars of {@code --jar} and the
 * default folder, whatever {@code --no-default-jars} says, each runtime from the first folder that
 * has it; {@code getRuntimes} says which runtimes it loaded, and from where.
 *
 * @param dirs the folders, in the order they are looked up in; empty if leia doesn't run from {@code
 *     leia.jar}, as under Gradle without {@code -Dleia.runtimes}
 */
public record RuntimesDirs(List<Path> dirs) {

    public RuntimesDirs {
        dirs = List.copyOf(dirs);
    }

    /** One folder. */
    public static RuntimesDirs of(Path dir) {
        return new RuntimesDirs(List.of(dir));
    }

    /** No folder: leia doesn't run from {@code leia.jar}. */
    public static RuntimesDirs none() {
        return new RuntimesDirs(List.of());
    }

    /** The first folder, the one that leia looks in first. */
    public Optional<Path> dir() {
        return dirs.stream().findFirst();
    }

    /** Whether any of the folders is there. */
    public boolean exists() {
        return dirs.stream().anyMatch(Files::isDirectory);
    }

    /** The runtime's folder that {@code jar} is in, such as {@code runtimes/lucene10}, if it is in one. */
    public Optional<String> folderOf(Path jar) {
        Path parent = jar.getParent();
        if (dirs.isEmpty() || parent == null || parent.getFileName() == null) {
            return Optional.empty();
        }
        return dirs.contains(parent.getParent()) ? Optional.of("runtimes/" + parent.getFileName()) : Optional.empty();
    }
}
