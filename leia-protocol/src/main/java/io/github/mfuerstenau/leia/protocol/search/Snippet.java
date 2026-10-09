package io.github.mfuerstenau.leia.protocol.search;

/**
 * A part of a stored value around its first match, cut between two characters (ADR 0027).
 *
 * @param start the UTF-8 byte offset in the value where {@code text} starts
 * @param text the part of the value
 */
public record Snippet(int start, String text) {}
