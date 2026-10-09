package io.github.mfuerstenau.leia.protocol.search;

/**
 * Where a match is in a stored value, in UTF-8 bytes (ADR 0027).
 *
 * @param start the offset of the match's first byte
 * @param end the offset after the match's last byte; {@code start} for an empty match
 */
public record MatchSpan(int start, int end) {}
