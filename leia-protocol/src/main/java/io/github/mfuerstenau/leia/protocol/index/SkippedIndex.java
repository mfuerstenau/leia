package io.github.mfuerstenau.leia.protocol.index;

/**
 * An index directory that was found below the opened path but left out (ADR 0021): it failed to
 * open, or its fields conflict with those of the indexes before it.
 *
 * @param path the absolute, normalized index directory
 * @param message why it was left out
 */
public record SkippedIndex(String path, String message) {}
