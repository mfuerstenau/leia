package io.github.mfuerstenau.leia.protocol.check;

import java.util.OptionalLong;

/**
 * A segment that {@code startCheckIndex} can check.
 *
 * @param name the segment's name, such as {@code _0}
 * @param maxDoc its documents, deleted ones included
 * @param sizeBytes the size of its files; absent if one of them can't be stat'ed
 */
public record CheckTargetSegment(String name, int maxDoc, OptionalLong sizeBytes) {}
