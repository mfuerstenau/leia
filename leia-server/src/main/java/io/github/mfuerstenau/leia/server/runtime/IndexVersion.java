package io.github.mfuerstenau.leia.server.runtime;

/**
 * The Lucene version of an index, as {@link SegmentsHeader} reads it (ADR 0044).
 *
 * @param major the major that picks the runtime: the one the index was created with, or for an index
 *     before 7.0 the oldest of its segments
 * @param writtenBy the version for messages, such as {@code "3.x"}, {@code "8.11.4"} or {@code "9.12.3,
 *     created with 8"}
 */
public record IndexVersion(int major, String writtenBy) {}
