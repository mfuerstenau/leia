package io.github.mfuerstenau.leia.core;

import java.nio.file.Path;

/**
 * An index directory that was found but left out when its path was opened (ADR 0021): it failed to
 * open, or its fields conflict with those of the indexes before it.
 *
 * @param path the absolute, normalized index directory
 * @param message why it was left out
 */
public record SkippedIndex(Path path, String message) {}
