package io.github.mfuerstenau.leia.core;

import java.nio.file.Path;
import java.util.List;

/**
 * What {@link ExtensionJars#add} did (ADR 0024).
 *
 * @param jars the jars that were loaded, as absolute paths, in the order they were given
 * @param alreadyLoaded the jars that had been loaded before, and weren't loaded again
 * @param added the names that the new jars added
 */
public record AddedJars(List<Path> jars, List<Path> alreadyLoaded, JarComponents added) {

    public AddedJars {
        jars = List.copyOf(jars);
        alreadyLoaded = List.copyOf(alreadyLoaded);
    }
}
