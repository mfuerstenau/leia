package io.github.mfuerstenau.leia.protocol.commits;

/**
 * A {@code segments_N} file of the index directory that could not be read (ADR 0021).
 *
 * @param segmentsFile its name
 * @param message why it could not be read
 */
public record UnreadableCommit(String segmentsFile, String message) {}
