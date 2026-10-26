package io.github.mfuerstenau.leia.protocol.check;

import java.util.List;
import java.util.Optional;

/**
 * An index of the open path to check, and optionally only some of its segments (ADR 0093).
 *
 * @param path the index directory, as {@code openIndex} gives it in {@code indexes[].path} or {@code
 *     skipped[].path}
 * @param segments the names of the segments of its latest commit to check, such as {@code _0}; not
 *     empty if present, without duplicates; absent means the whole index
 */
public record CheckTarget(String path, Optional<List<String>> segments) {}
