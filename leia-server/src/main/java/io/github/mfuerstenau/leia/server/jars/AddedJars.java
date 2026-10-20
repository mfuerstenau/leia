package io.github.mfuerstenau.leia.server.jars;

import io.github.mfuerstenau.leia.runtime.Extensions;
import java.nio.file.Path;
import java.util.List;

/**
 * What {@link JarChain#add} did (ADR 0024).
 *
 * @param jars the jars that were loaded, as absolute paths, in the order they were given
 * @param alreadyLoaded the jars that had been loaded before, and weren't loaded again
 * @param added the names that the new jars added
 */
public record AddedJars(List<Path> jars, List<Path> alreadyLoaded, Extensions.Added added) {

    public AddedJars {
        jars = List.copyOf(jars);
        alreadyLoaded = List.copyOf(alreadyLoaded);
    }
}
