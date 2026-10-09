package io.github.mfuerstenau.leia.protocol.jars;

import java.util.List;

/**
 * Result of {@code getJars} (ADR 0024).
 *
 * @param jars the loaded jars, as absolute paths, in the order they were loaded: those of the
 *     command line first, then those that {@code addJars} added
 */
public record GetJarsResult(List<String> jars) {}
