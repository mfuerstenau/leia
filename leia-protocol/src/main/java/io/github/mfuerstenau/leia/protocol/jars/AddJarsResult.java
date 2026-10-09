package io.github.mfuerstenau.leia.protocol.jars;

import java.util.List;

/**
 * Result of {@code addJars} (ADR 0024).
 *
 * @param jars the jars that were loaded, as absolute paths, in the order they were given, a
 *     directory's sorted by name
 * @param alreadyLoaded the jars that had been loaded before, and weren't loaded again
 * @param added the names that the new jars added
 */
public record AddJarsResult(List<String> jars, List<String> alreadyLoaded, JarComponents added) {}
