package io.github.mfuerstenau.leia.protocol.filter;

/**
 * Result of {@code releaseFilter}.
 *
 * @param released whether the filter was kept until now; false if it is unknown, was replaced or
 *     released before, or belonged to an index that was closed or reopened
 */
public record ReleaseFilterResult(boolean released) {}
