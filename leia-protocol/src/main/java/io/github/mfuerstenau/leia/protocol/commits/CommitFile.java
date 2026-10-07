package io.github.mfuerstenau.leia.protocol.commits;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * A file of a commit.
 *
 * @param name the file name
 * @param segment the segment it belongs to; absent for {@code segments_N}
 * @param size the size in bytes; absent if the file can't be stat'ed
 */
public record CommitFile(String name, Optional<String> segment, OptionalLong size) {}
