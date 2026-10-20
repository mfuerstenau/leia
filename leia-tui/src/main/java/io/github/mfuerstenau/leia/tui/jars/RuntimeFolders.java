package io.github.mfuerstenau.leia.tui.jars;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The folder {@code runtimes} beside {@code leia.jar}, with a folder for each runtime, such as {@code
 * lucene9} (ADR 0044, ADR 0073). The backend loads it at every start, before the jars of {@code
 * --jar} and the default folder, whatever {@code --no-default-jars} says; {@code getRuntimes} says
 * which runtimes it loaded.
 *
 * @param dir the folder; empty if leia doesn't run from {@code leia.jar}, as under Gradle without
 *     {@code -Dleia.runtimes}
 */
public record RuntimeFolders(Optional<Path> dir) {

    /** No folder: leia doesn't run from {@code leia.jar}. */
    public static RuntimeFolders none() {
        return new RuntimeFolders(Optional.empty());
    }

    /** Whether the folder is there. */
    public boolean exists() {
        return dir.filter(Files::isDirectory).isPresent();
    }

    /** The runtime's folder that {@code jar} is in, such as {@code runtimes/lucene10}, if it is in one. */
    public Optional<String> folderOf(Path jar) {
        Path parent = jar.getParent();
        if (dir.isEmpty() || parent == null || parent.getFileName() == null) {
            return Optional.empty();
        }
        return dir.get().equals(parent.getParent())
                ? Optional.of("runtimes/" + parent.getFileName())
                : Optional.empty();
    }
}
